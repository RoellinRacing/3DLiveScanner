#include <cstdio>
#include <cmath>
#include <sstream>
#include <arcore/service.h>
#include <exporter/cad_export.h>
#include <exporter/csvposes.h>
#include <exporter/floorpln.h>
#include <postproc/optimizer.h>
#include <postproc/poisson.h>
#include <postproc/texturize.h>
#include <zconf.h>
#include "app.h"

static oc::App app;

namespace oc {

    void AnalyseCallback(int current, int count) {
        if (count <= 0) {
            app.SetEvent("CONVERT");
        } else {
            std::ostringstream ss;
            ss << "ANALYSE ";
            ss << current;
            ss << "/";
            ss << count;
            app.SetEvent(ss.str());
        }
    }

    App::App() :  ar(nullptr),
                  gyro(true),
                  oriented(false),
                  movex(0),
                  lastMovex(0),
                  movey(0),
                  lastMovey(0),
                  movez(0),
                  lastMovez(0),
                  orbit(0),
                  lastOrbit(0),
                  pitch(0),
                  lastPitch(0),
                  yaw(0),
                  lastYaw(0),
                  lowest(0),
                  viewportWidth(1),
                  viewportHeight(1) {
    }

    bool App::OnARServiceConnected(JNIEnv *env, jobject context, double res, double dmin,
                                   double dmax, int noise, bool holesFilling, bool poseCorrection,
                                   bool distortion, bool offset, bool flashlight, int mode,
                                   bool clearing, std::string dataset_path) {

        if (ar) {
            delete ar;
            ar = nullptr;
        }
        ar = new ARCoreService(env, context, (ARCoreService::Mode)mode, flashlight);
        if (!ar || !ar->IsReady()) {
            delete ar;
            ar = nullptr;
            return false;
        }
        ar->SetOffset(offset ? (float) fabs(res) : 0);
        ar->SetResolution((float)fabs(res));
        reconstruction.Setup(res, dmin, dmax, noise, holesFilling, poseCorrection, distortion, clearing, dataset_path);

        std::string access = reconstruction.dataset->GetPath() + "/test.txt";
        FILE* file = fopen(access.c_str(), "w");
        if (file) {
            fclose(file);
            return true;
        } else {
            return false;
        }
    }

    int App::GetRuntimeCapabilities() {
        return ar ? ar->GetRuntimeCapabilities() : 0;
    }

    void App::OnSurfaceChanged(int width, int height, bool fullhd) {
        reconstruction.render_mutex_.lock();
        if (ar) {
            ar->OnDisplayGeometryChanged(0, width, height, fullhd);
        }
        reconstruction.scene.SetupViewPort(width, height);
        viewportWidth = width;
        viewportHeight = height;
        selector.Init(width, height);
        reconstruction.render_mutex_.unlock();
    }

    bool App::OnDrawFrame(bool facemode, float compassYaw, int viewmode, bool anchors, bool grid, bool smooth) {
        reconstruction.render_mutex_.lock();

        //clear screen
        glClearColor(0, 0, 0, 0);
        glClearStencil(0);
        glDisable(GL_BLEND);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

        //camera transformation
        glm::vec3 pos = reconstruction.scene.renderer->camera.position;
        if (facemode) {
            lastOrbit = orbit;
            lastPitch = pitch;
            lastYaw   = yaw;
            viewmode  = 1;
            reconstruction.scene.renderer->camera.position = glm::vec3(lastMovex, lastMovez, lastMovey);
            reconstruction.scene.renderer->camera.rotation = glm::quat(glm::vec3(-lastYaw, -lastPitch, 0));
            reconstruction.scene.renderer->camera.position += reconstruction.scene.renderer->camera.rotation * glm::vec3(0, 0, lastOrbit);
            reconstruction.scene.renderer->camera.scale    = glm::vec3(1, 1, 1);
        } else if (!gyro) {
            if (smooth) {
                lastMovex = lastMovex * 0.9f + movex * 0.1f;
                lastMovey = lastMovey * 0.9f + movey * 0.1f;
                lastMovez = lastMovez * 0.9f + movez * 0.1f;
                lastOrbit = lastOrbit * 0.95f + orbit * 0.05f;
                lastPitch = lastPitch * 0.95f + pitch * 0.05f;
                lastYaw   = lastYaw   * 0.95f + yaw   * 0.05f;
            } else {
                lastMovex = movex;
                lastMovey = movey;
                lastMovez = movez;
                lastOrbit = orbit;
                lastPitch = pitch;
                lastYaw   = yaw;
            }
            reconstruction.scene.renderer->camera.position = glm::vec3(lastMovex, lastMovez, lastMovey);
            reconstruction.scene.renderer->camera.rotation = glm::quat(glm::vec3(lastYaw, lastPitch, 0));
            reconstruction.scene.renderer->camera.scale    = glm::vec3(1, 1, 1);
            if (lastOrbit > 0) {
                reconstruction.scene.renderer->camera.position += reconstruction.scene.renderer->camera.rotation * glm::vec3(0, 0, lastOrbit);
            }
        }

        //AR and reconstruction thread initialisation
        bool zoomable = (viewmode == 0) || (viewmode == 3);
        if (ar) {
            ar->SetNVScheme(ARCoreCamera::WHITE2BLUE);
            if (reconstruction.paused) {
                ar->OnResume();
                reconstruction.paused = false;
            }
            if (!oriented && ar->HasCoordinateSystem()) {
                reconstruction.dataset->WriteYaw(compassYaw);
                oriented = true;
            }

            if (ar->Process()) {
                if (ar->GetPoseDiff() < 25) {
                    reconstruction.lost = false;
                    reconstruction.tracked = true;
                }
                glm::mat4 pose = ar->GetPose()[OPENGL_CAMERA];
                reconstruction.scene.renderer->camera.SetTransformation(pose);
                if (anchors) {
                    reconstruction.scene.UpdateAnchors(ar->GetActiveAnchors(), movez + 5.0f);
                }
                if (!facemode)
                    reconstruction.scene.UpdateFrustum(ar->GetView());
                pos = reconstruction.scene.renderer->camera.position;

                float maxDiff = 25;
                if (reconstruction.binder_mutex_.try_lock()) {
                    glm::mat4 lastMatrix = reconstruction.frame_calibration * reconstruction.frame_viewmat;
                    Image* lastFrame = 0;
                    if (reconstruction.frame_image) {
                        lastFrame = reconstruction.frame_image;
                    }

                    reconstruction.frame_calibration = ar->GetProjection();
                    reconstruction.frame_distortion = ar->GetDistortion();
                    reconstruction.frame_image = ar->GetImage();
                    reconstruction.frame_pose = ar->GetPose();
                    reconstruction.frame_timestamp = double(clock() - reconstruction.start) / CLOCKS_PER_SEC;
                    reconstruction.frame_viewmat = ar->GetView();

                    if (!ar->IsFaceMode()) {

                        if ((reconstruction.frame_timestamp > 10) && reconstruction.scene.static_meshes_.empty()) {
                            reconstruction.start = clock();
                            reconstruction.binder_mutex_.unlock();
                        } else if (ar->GetPoseDiff() < maxDiff) {
                            if (reconstruction.t3dr_is_running_) {
                                reconstruction.event_mutex_.lock();
                                sprintf(reconstruction.pose_feedback, "");
                                reconstruction.event_mutex_.unlock();
                            }

                            reconstruction.frame_sparse = ar->GetMode() == ARCoreService::HUAWEI_SFM;
                            if (reconstruction.t3dr_is_running_) {
                                reconstruction.frame_points = ar->GetPointCloud(maxDiff);

                                int scale = reconstruction.frame_image->GetWidth() / 360;
                                if (reconstruction.request_image) {
                                    delete reconstruction.request_image;
                                    reconstruction.request_image = 0;
                                }

                                if (reconstruction.frame_points.empty()) {
                                    reconstruction.binder_mutex_.unlock();
                                } else if (reconstruction.pose_correction && lastFrame) {

                                    //create requested image for pose correction
                                    reconstruction.request_image = reconstruction.frame_image->Downscale(scale);
                                    reconstruction.request_distance = 0.015f;
                                    reconstruction.AddPoses();
                                    int w = reconstruction.request_image->GetWidth();
                                    int h = reconstruction.request_image->GetHeight();
                                    if (!reconstruction.renderer) {
                                        reconstruction.renderer = new GLRenderer();
                                        reconstruction.renderer->Init(reconstruction.scene.renderer->width, reconstruction.scene.renderer->height, w, h);
                                    }

                                    //update projective texture
                                    if (reconstruction.request_newprojection) {
                                        if (reconstruction.scene.projectTexture >= 0) {
                                            Image temp(255, 255, 255, 255);
                                            temp.SetTexture(reconstruction.scene.projectTexture);
                                            temp.UpdateTexture();
                                            temp.SetTexture(reconstruction.scene.projectDepth);
                                            temp.UpdateTexture();
                                        }
                                        reconstruction.renderer->Rtt(true);
                                        reconstruction.scene.BindDepthShader();
                                        OnDrawScan(pos, true);
                                        reconstruction.renderer->Rtt(false);
                                        Image* temp = reconstruction.renderer->ReadRtt();
                                        reconstruction.scene.projectDepth = GLSL::Image2GLTexture(temp);
                                        reconstruction.scene.projectTexture = GLSL::Image2GLTexture(lastFrame);
                                        reconstruction.scene.projectMatrix = lastMatrix;
                                        delete temp;
                                    }

                                    //pose correction
                                    glClearColor(1, 1, 1, 1);
                                    reconstruction.renderer->Rtt(true);
                                    reconstruction.scene.BindMixedShader();
                                    glViewport(0, 0, w, h);
                                    OnDrawScan(pos, true);
                                    reconstruction.renderer->Rtt(false);
                                    if (reconstruction.rendered_image) {
                                        delete reconstruction.rendered_image;
                                    }
                                    reconstruction.rendered_image = reconstruction.renderer->ReadRtt(0, 0, w, h);
                                    glClearColor(0, 0, 0, 1);
                                    reconstruction.renderer->Rtt(true);
                                    reconstruction.scene.BindDepthShader();
                                    glViewport(0, 0, w, h);
                                    OnDrawScan(pos, true);
                                    reconstruction.renderer->Rtt(false);
                                    if (reconstruction.rendered_depth) {
                                        delete reconstruction.rendered_depth;
                                    }
                                    reconstruction.rendered_depth = reconstruction.renderer->ReadRtt(0, 0, w, h);
                                    reconstruction.Start(Reconstruction::POSE_CORRECTION);
                                } else {
                                    reconstruction.Start(Reconstruction::RECONSTRUCTION);
                                }
                            } else {
                                reconstruction.Start(Reconstruction::DUMMY);
                            }
                        } else {
                            if (reconstruction.tracked) {
                                reconstruction.jumped = true;
                                reconstruction.event_mutex_.lock();
                                sprintf(reconstruction.pose_feedback, "MT_JUMP");
                                reconstruction.event_mutex_.unlock();
                            }
                            reconstruction.binder_mutex_.unlock();
                        }
                    } else {
                        reconstruction.binder_mutex_.unlock();
                    }

                    if (lastFrame) {
                        delete lastFrame;
                    }
                }
            } else {
                reconstruction.lost = true;
            }
            reconstruction.scene.renderer->camera.projection = ar->GetProjection();
        }
        if (facemode) {
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
        } else {
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
        }
        if (zoomable) {
            glm::vec4 move = reconstruction.scene.renderer->camera.GetTransformation() * glm::vec4(0, 0, movez, 0);
            reconstruction.scene.renderer->camera.position += glm::vec3(move.x, move.y, move.z);
        }

        //render virtual objects (static meshes, frustum, selection, grid...)
        reconstruction.scene.Render(gyro && zoomable);
        if (grid) {
            if (ar && zoomable) {
                reconstruction.scene.RenderGrid(glm::vec3((int)pos.x, pos.y - 2, (int)pos.z), 20, 0x808000);
            } else if (!gyro) {
                bool floorplan = false;
                for (Mesh& m : reconstruction.scene.static_meshes_) {
                    if (m.image) {
                        int separator = 0;
                        std::string name = m.image->GetName();
                        for (int i = 0; i < name.size(); i++) {
                            if (name[i] == '/') {
                                separator = i;
                            }
                        }
                        if (strcmp(name.substr(separator).c_str(), "/floorplan.png") == 0) {
                            floorplan = true;
                        }
                    }
                }
                if (!facemode && !floorplan) {
                    int z = lastOrbit > 0 ? lastOrbit : lastMovez;
                    int c = glm::max(0, 128 - abs(z));
                    int color = c * 256 + c * 65536;
                    reconstruction.scene.RenderGrid(glm::vec3((int)lastMovex, lowest - 0.001f, (int)lastMovey), 40, color);
                }
            }
        }

        //render scene
        glEnable(GL_BLEND);
        glEnable(GL_STENCIL_TEST);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        reconstruction.scene.BindMixedShader();
        bool output = OnDrawScan(pos, zoomable);
        glDisable(GL_BLEND);

        //camera preview
        if (ar && ((viewmode == 1) || (viewmode == 2))) {
            if (!reconstruction.scene.showNormals) {
                glStencilFunc(GL_EQUAL, 1, 0xFF);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
                ar->RenderCamera(ARCoreCamera::NONE);
            }
            glStencilFunc(GL_NOTEQUAL, 1, 0xFF);
            ar->RenderCamera(viewmode == 1 ? ARCoreCamera::GRAYSCALE : ARCoreCamera::DEPTH_INV);
        }
        if (ar && (viewmode == 0)) {
            glDisable(GL_DEPTH_TEST);
            int w = reconstruction.scene.renderer->width;
            int h = reconstruction.scene.renderer->height;
            glViewport(0, 5 * h / 6, w / 6, h / 6);
            ar->RenderCamera();
            glViewport(5 * w / 6, 5 * h / 6, w / 6, h / 6);
            ar->RenderCamera(ARCoreCamera::DEPTH_INV, 4);
            glViewport(0, 0, w, h);
            glEnable(GL_DEPTH_TEST);
        }
        reconstruction.render_mutex_.unlock();

        int error = glGetError();
        if (error > 0) {
            LOGI("GLERROR %d", error);
        }
        return output;
    }

    bool App::OnDrawScan(glm::vec3 pos, bool zoomable) {
        bool output = !reconstruction.scene.static_meshes_.empty();
        float step = reconstruction.scan.Resolution() * 16.0f;
        float limit = 2 * glm::min(reconstruction.scan.Resolution(), 0.03f) * 100.0f;
        if (reconstruction.scan.Resolution() > 0.05f) {
            limit = 10000;
        }
        GLSL::CurrentShader()->UniformFloat("u_uniformBegin", limit - step - step);
        GLSL::CurrentShader()->UniformFloat("u_uniformFactor", 1.0f / step);
        GLSL::CurrentShader()->UniformVec3("u_uniformCamera", pos.x, pos.y, pos.z);
        for (std::pair<GridIndex, Tango3DR_Mesh*> s : reconstruction.scan.Data()) {
            bool ok = true;
            for (int i = 0; i < 3; i++) {
                if (abs(pos[i] - s.first.indices[i] * step) > limit) {
                    ok = false;
                    break;
                }
            }
            if (ok && (!reconstruction.lost || zoomable)) {
                reconstruction.scene.renderer->Render(&s.second->vertices[0][0], &s.second->normals[0][0], 0,
                                                      (unsigned int*)&s.second->colors[0][0],
                                                      s.second->num_faces * 3, &s.second->faces[0][0]);
                output = true;
            }
        }
        return output;
    }

    void App::OnToggleButtonClicked(bool t3dr_is_running) {
        reconstruction.binder_mutex_.lock();
        reconstruction.t3dr_is_running_ = t3dr_is_running;
        reconstruction.binder_mutex_.unlock();
    }

    void App::OnClearButtonClicked() {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        if (ar) ar->Clear();
        reconstruction.frame_index = 0;
        reconstruction.scan.Clear();
        reconstruction.request_newprojection = true;
        reconstruction.texturize.Clear(reconstruction.dataset);
        for (Mesh& m : reconstruction.scene.static_meshes_)
            m.Destroy();
        reconstruction.scene.static_meshes_.clear();

        reconstruction.event_mutex_.lock();
        sprintf(reconstruction.pose_feedback, "");
        reconstruction.event_mutex_.unlock();

        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
    }

    void App::OnUndoButtonClicked(bool fromUser, bool texturize) {
        reconstruction.Undo(fromUser, texturize);
    }

    void App::OnUndoPreviewUpdate(int frames) {
        reconstruction.PreviewChange(frames);
    }

    void App::OnPause() {
        if (ar && !reconstruction.paused) {
            ar->OnPause();
            reconstruction.paused = true;
        }
    }

    bool App::FinalizeDatasetCapture() {
        OnToggleButtonClicked(false);
        OnPause();
        // Every accepted frame writes state.txt last. Waiting for the binder
        // lock guarantees that an in-flight reconstruction has finished all
        // image, pose and point-cloud writes before Java moves the directory.
        reconstruction.binder_mutex_.lock();
        bool usable = reconstruction.dataset != nullptr &&
                      reconstruction.texturize.GetLatestIndex(reconstruction.dataset) >= 0;
        reconstruction.binder_mutex_.unlock();
        LOGI("Dataset capture finalized: %s", usable ? "usable" : "empty");
        return usable;
    }

    void App::Extract(std::string path, int mode) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        if (mode == -100) {
            ExporterFloorplan floorplan;
            floorplan.SetCallback(AnalyseCallback);
            floorplan.Process(reconstruction.dataset, path);
        } else if (mode == -200) {

            //get orientation
            float yaw = glm::radians(reconstruction.dataset->ReadYaw());
            float s = glm::sin(-yaw);
            float c = glm::cos(-yaw);
            glm::vec3 p, v;

            //export pointcloud
            std::vector<Mesh> mesh = reconstruction.scan.Export();
            for (Mesh& m : mesh) {
                for (unsigned int i = 0; i < m.vertices.size(); i++) {
                    p = m.vertices[i];
                    v = m.vertices[i];
                    v.x = p.x * s - p.z * c;
                    v.z = p.x * c + p.z * s;
                    m.vertices[i] = v;
                }
                m.indices.clear();
                m.MirrorZ();
                m.SwapYZ();
            }
            File3d(path, true).WriteModel(mesh);
        }
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
    }

    std::string App::GetEvent() {
        return reconstruction.GetEvent();
    }

    void App::SetEvent(std::string value) {
        reconstruction.event_mutex_.lock();
        reconstruction.texturize.SetEvent(value);
        reconstruction.event_mutex_.unlock();
    }

    bool App::Load(std::string filename) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();

        File3d io(filename, false);
        io.ReadModel(INT_MAX, reconstruction.scene.static_meshes_);
        if (io.GetType() == TYPE::PLY) {
            for (Mesh& m : reconstruction.scene.static_meshes_) {
                m.SwapYZ();
                m.MirrorZ();
            }
        }

        int count = 0;
        glm::vec3 min(9999), max(-9999);
        for (Mesh& m : reconstruction.scene.static_meshes_) {
            count += m.vertices.size();
            for (glm::vec3& v : m.vertices) {
                if (min.x > v.x) min.x = v.x;
                if (min.y > v.y) min.y = v.y;
                if (min.z > v.z) min.z = v.z;
                if (max.x < v.x) max.x = v.x;
                if (max.y < v.y) max.y = v.y;
                if (max.z < v.z) max.z = v.z;
            }
            if (io.GetType() != TYPE::PLY) {
                m.GenerateFaceNormals();
            }
        }
        lastMovex = (min.x + max.x) * 0.5f;
        lastMovez = (min.y + max.y) * 0.5f;
        lastMovey = (min.z + max.z) * 0.5f;
        lowest = min.y;
        LOGI("Loaded model with %d vertices", count);
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
        return count > 0;
    }

    void App::Optimize(std::string filename) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        Optimizer().Process(filename);
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
    }

    bool App::Save(std::string filename) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        int count = 0;
        if (ar && ar->IsFaceMode()) {
            Mesh face = ar->GetFace();
            count = face.vertices.size();
            face.image = reconstruction.frame_image;

            std::string name = filename.substr(0, filename.size() - 4) + "_face.jpg";
            face.image->SetName(name);
            face.image->Write(name);

            reconstruction.scene.static_meshes_.clear();
            reconstruction.scene.static_meshes_.push_back(face);
            File3d(filename, true).WriteModel(reconstruction.scene.static_meshes_);
        } else {
            for (Mesh& m : reconstruction.scene.static_meshes_)
                m.Destroy();
            reconstruction.scene.static_meshes_.clear();

            reconstruction.lost = false;
            sprintf(reconstruction.pose_feedback, "");
            for (Mesh& m : reconstruction.scan.Export()) {
                m.Reindex();
                m.GenerateFaceNormals();
                reconstruction.scene.static_meshes_.push_back(m);
            }
            File3d(filename, true).WriteModel(reconstruction.scene.static_meshes_);
            for (Mesh& m : reconstruction.scene.static_meshes_)
                count += m.vertices.size();

            if (ar) ar->Clear();
            reconstruction.scan.Clear();
            for (Mesh& m : reconstruction.scene.static_meshes_)
                m.Destroy();
            reconstruction.scene.static_meshes_.clear();

            if (count > 0) {
                ExporterCSVPoses().Process(reconstruction.dataset, reconstruction.dataset->GetPath() + "/posesOBJ.csv", false);
                ExporterCSVPoses().Process(reconstruction.dataset, reconstruction.dataset->GetPath() + "/posesPLY.csv", true);
            }
        }
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
        return count != 0;
    }

    void App::SaveWithTextures(std::string filename) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        reconstruction.lost = false;
        int index = 0;
        std::vector<std::string> names;
        for (Mesh& m : reconstruction.scene.static_meshes_) {
            if (m.image && m.imageOwner) {
                std::ostringstream ss;
                ss << filename.substr(0, filename.size() - 4).c_str();
                ss << "_";
                ss << index++;
                ss << ".png";
                names.push_back(m.image->GetName());
                bool jpg = m.image->GetExtension().compare("jpg") == 0;
                if (jpg) m.image->UpsideDown();
                m.image->SetName(ss.str());
                m.image->Write(ss.str());
                if (jpg) m.image->UpsideDown();
            }
        }
        index = 0;
        File3d(filename, true).WriteModel(reconstruction.scene.static_meshes_);
        for (Mesh& m : reconstruction.scene.static_meshes_)
            if (m.image && m.imageOwner)
                m.image->SetName(names[index++]);
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
    }

    bool App::ExportEngineering(std::string filename, int format) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        bool ok = false;
        if (format == 0) {
            ok = CadExport::WriteBinaryStl(filename, reconstruction.scene.static_meshes_);
        } else if (format == 1) {
            ok = CadExport::Write3mfModelXml(filename, reconstruction.scene.static_meshes_);
        } else if (format == 2) {
            ok = CadExport::WriteAsciiPly(filename, reconstruction.scene.static_meshes_);
        }
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
        return ok;
    }

    void App::SetTextureParams(int detail, int res, int count) {
        reconstruction.binder_mutex_.lock();
        reconstruction.texturize.SetTextureParams(detail, res, count);
        reconstruction.binder_mutex_.unlock();
    }

    void App::Texturize(std::string input, std::string output, bool poisson, bool twoPass) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        reconstruction.lost = false;
        reconstruction.scan.Clear();

        if (poisson) {
            if (!reconstruction.InitTexturing(input, true)) return;
            reconstruction.texturize.Process(input, false, false);
            reconstruction.texturize.SetEvent("POISSON");
            Poisson().Process(input);
            reconstruction.texturize.SetEvent("");
        }

        //texturize
        if (twoPass) {
            if (!reconstruction.InitTexturing(input, true)) return;
            reconstruction.texturize.Process(output, true, false);

            std::vector<int> frames;
            {
                oc::Texturize texturize;
                texturize.SetCallback(AnalyseCallback);
                texturize.Process(reconstruction.dataset, output, false);
                SetEvent("");
                for (int i : texturize.GetFrames()) {
                    frames.push_back(i);
                }
            }

            if (!reconstruction.InitTexturing(output, false)) return;
            reconstruction.texturize.ApplyFrames(reconstruction.dataset, frames);
            reconstruction.texturize.Process(output, true, poisson);
        } else {
            if (!reconstruction.InitTexturing(input, false)) return;
            reconstruction.texturize.ApplyFrames(reconstruction.dataset);
            reconstruction.texturize.Process(output, true, poisson);
        }

        //reorient
        glm::vec3 p, v;
        char buffer[1024];
        std::vector<std::string> model;
        float yaw = glm::radians(reconstruction.dataset->ReadYaw());
        float s = glm::sin(-yaw);
        float c = glm::cos(-yaw);
        FILE* file = fopen(output.c_str(), "r");
        while (true) {
            if (!fgets(buffer, 1024, file))
                break;
            std::string sbuf = buffer;
            while(!sbuf.empty() && isspace(sbuf[0])) {
                sbuf = sbuf.substr(1);
            }
            if ((sbuf[0] == 'v') && (sbuf[1] != 't')) {
                if (sbuf[1] == ' ') {
                    sscanf(sbuf.c_str(), "v %f %f %f", &p.x, &p.y, &p.z);
                } else {
                    sscanf(sbuf.c_str(), "vn %f %f %f", &p.x, &p.y, &p.z);
                }
                v.x = p.x * s - p.z * c;
                v.y = p.y;
                v.z = p.x * c + p.z * s;
                if (sbuf[1] == ' ') {
                    sprintf(buffer, "v %f %f %f\n", v.x, v.y, v.z);
                } else {
                    sprintf(buffer, "vn %f %f %f\n", v.x, v.y, v.z);
                }
                model.emplace_back(buffer);
            } else {
                model.push_back(sbuf);
            }
        }
        fclose(file);
        file = fopen(output.c_str(), "w");
        for (std::string& s : model) {
            fprintf(file, "%s", s.c_str());
        }
        fclose(file);

        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
    }

    std::vector<float> App::PickMeasurementPoint(float x, float y) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        std::vector<float> output;
        if (!reconstruction.scene.renderer || reconstruction.scene.static_meshes_.empty()) {
            reconstruction.render_mutex_.unlock();
            reconstruction.binder_mutex_.unlock();
            return output;
        }

        // Batch-rasterize a small cross around the requested pixel.  The center
        // is the actual measurement point; its neighbours provide a local
        // continuity score without pretending to be an absolute accuracy value.
        std::vector<glm::vec2> taps;
        taps.push_back(glm::vec2(x, y));
        taps.push_back(glm::vec2(x + 4, y));
        taps.push_back(glm::vec2(x - 4, y));
        taps.push_back(glm::vec2(x, y + 4));
        taps.push_back(glm::vec2(x, y - 4));
        glm::mat4 matrix = reconstruction.scene.renderer->camera.projection *
                           reconstruction.scene.renderer->camera.GetView();
        std::vector<glm::vec3> hits = selector.Transform(
                reconstruction.scene.static_meshes_, matrix, taps, true);

        if (!hits.empty() && (fabs(hits[0].x) < 90000) &&
            (fabs(hits[0].y) < 90000) && (fabs(hits[0].z) < 90000)) {
            int valid = 1;
            float spread = 0;
            for (unsigned int i = 1; i < hits.size(); ++i) {
                if ((fabs(hits[i].x) < 90000) && (fabs(hits[i].y) < 90000) &&
                    (fabs(hits[i].z) < 90000)) {
                    valid++;
                    spread = glm::max(spread, glm::length(hits[i] - hits[0]));
                }
            }
            float cameraDistance = glm::max(
                    0.01f,
                    glm::length(reconstruction.scene.renderer->camera.position - hits[0]));
            float continuity = 1.0f - glm::clamp(
                    spread / (cameraDistance * 0.03f), 0.0f, 1.0f);
            float quality = 0.55f * ((float) valid / (float) hits.size()) +
                            0.45f * continuity;
            output.push_back(hits[0].x);
            output.push_back(hits[0].y);
            output.push_back(hits[0].z);
            output.push_back(quality);
        }
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
        return output;
    }

    std::vector<float> App::ProjectMeasurementPoints(const std::vector<float>& points) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        std::vector<float> output;
        if (reconstruction.scene.renderer && (viewportWidth > 0) && (viewportHeight > 0)) {
            glm::mat4 matrix = reconstruction.scene.renderer->camera.projection *
                               reconstruction.scene.renderer->camera.GetView();
            for (unsigned int i = 0; i + 2 < points.size(); i += 3) {
                glm::vec4 projected = matrix * glm::vec4(
                        points[i], points[i + 1], points[i + 2], 1.0f);
                bool visible = projected.w > 0.00001f;
                if (visible)
                    projected /= projected.w;
                visible = visible && (projected.z >= -1) && (projected.z <= 1);
                output.push_back((projected.x * 0.5f + 0.5f) * viewportWidth);
                output.push_back((0.5f - projected.y * 0.5f) * viewportHeight);
                output.push_back(visible ? 1.0f : 0.0f);
            }
        }
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
        return output;
    }

    bool App::ApplyUniformScale(float factor, float anchorX, float anchorY, float anchorZ) {
        if (!std::isfinite(factor) || (factor < 0.01f) || (factor > 100.0f))
            return false;

        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        if (reconstruction.scene.static_meshes_.empty()) {
            reconstruction.render_mutex_.unlock();
            reconstruction.binder_mutex_.unlock();
            return false;
        }

        glm::vec3 anchor(anchorX, anchorY, anchorZ);
        for (const Mesh& mesh : reconstruction.scene.static_meshes_) {
            for (const glm::vec3& vertex : mesh.vertices) {
                glm::vec3 scaled = anchor + (vertex - anchor) * factor;
                if (!std::isfinite(scaled.x) || !std::isfinite(scaled.y) ||
                    !std::isfinite(scaled.z)) {
                    reconstruction.render_mutex_.unlock();
                    reconstruction.binder_mutex_.unlock();
                    return false;
                }
            }
        }

        Backup();
        for (Mesh& mesh : reconstruction.scene.static_meshes_) {
            for (glm::vec3& vertex : mesh.vertices)
                vertex = anchor + (vertex - anchor) * factor;
            mesh.InvalidateGeometryCache();
        }

        // Keep the floor reference consistent. Camera coordinates deliberately
        // stay unchanged: CameraControl owns the matching Java-side target and
        // would otherwise restore stale, unscaled coordinates on the next touch.
        lowest = anchorY + (lowest - anchorY) * factor;
        editor.SetCenter(anchor);
        reconstruction.scene.uniformPos = anchor;
        reconstruction.scene.UpdateSelected(false);

        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
        return true;
    }

    float App::GetDistance(float x1, float y1, float x2, float y2) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        glm::mat4 matrix = reconstruction.scene.renderer->camera.projection * reconstruction.scene.renderer->camera.GetView();
        glm::vec3 a = selector.Transform(reconstruction.scene.static_meshes_, matrix, x1, y1);
        glm::vec3 b = selector.Transform(reconstruction.scene.static_meshes_, matrix, x2, y2);
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
        return glm::length(a - b);
    }

    float App::GetFloorLevel(float x, float y, float z) {
        reconstruction.binder_mutex_.lock();
        reconstruction.render_mutex_.lock();
        float output = INT_MAX;
        glm::vec3 p = glm::vec3(x, z, y);
        for (unsigned int i = 0; i < reconstruction.scene.static_meshes_.size(); i++) {
            float value = reconstruction.scene.static_meshes_[i].GetFloorLevel(p);
            if (value < INT_MIN + 1000)
                continue;
            if (output > value)
                output = value;
        }
        if (output > INT_MAX - 1000)
            output = INT_MIN;
        reconstruction.render_mutex_.unlock();
        reconstruction.binder_mutex_.unlock();
        return output;
    }

    void App::Backup() {
        EditorBackup backup;
        for (Mesh& m : reconstruction.scene.static_meshes_) {
            backup.mesh.push_back(m);
        }
        backups.push_back(backup);
        if (backups.size() > 3) {
            backups.erase(backups.begin());
        }
    }

    void App::Restore() {
        reconstruction.render_mutex_.lock();
        if (!backups.empty()) {
            int index = backups.size() - 1;
            reconstruction.scene.static_meshes_.clear();
            for (Mesh& m : backups[index].mesh) {
                reconstruction.scene.static_meshes_.push_back(m);
            }
            backups.erase(backups.begin() + index);
        }
        reconstruction.render_mutex_.unlock();
    }

    void App::ApplyEffect(Effector::Effect effect, float value, int axis) {
        reconstruction.render_mutex_.lock();
        if (effect == Effector::CLONE)
            Backup();
        if (effect == Effector::DELETE)
            Backup();
        if (effect == Effector::MOVE)
            Backup();
        if (effect == Effector::ROTATE)
            Backup();
        if (effect == Effector::SCALE)
            Backup();
        editor.ApplyEffect(reconstruction.scene.static_meshes_, effect, value, axis);
        reconstruction.scene.vertex = reconstruction.scene.TexturedVertexShader();
        reconstruction.scene.fragment = reconstruction.scene.TexturedFragmentShader();
        reconstruction.render_mutex_.unlock();
    }

    void App::PreviewEffect(Effector::Effect effect, float value, int axis) {
        reconstruction.render_mutex_.lock();
        std::string vs = reconstruction.scene.TexturedVertexShader();
        std::string fs = reconstruction.scene.TexturedFragmentShader();
        editor.PreviewEffect(vs, fs, effect, axis);
        reconstruction.scene.vertex = vs;
        reconstruction.scene.fragment = fs;
        reconstruction.scene.uniform = value / 255.0f;
        reconstruction.render_mutex_.unlock();
    }

    void App::ShowNormals(bool on) {
        reconstruction.scene.showNormals = on;
    }

    void App::ApplySelection(float x, float y, bool triangle) {
        reconstruction.render_mutex_.lock();
        glm::mat4 matrix = reconstruction.scene.renderer->camera.projection * reconstruction.scene.renderer->camera.GetView();
        Backup();
        if (triangle)
          selector.SelectTriangle(reconstruction.scene.static_meshes_, matrix, x, y);
        else
          selector.SelectObject(reconstruction.scene.static_meshes_, matrix, x, y);
        glm::vec3 center = selector.GetCenter(reconstruction.scene.static_meshes_);
        editor.SetCenter(center);
        reconstruction.scene.uniformPos = center;
        reconstruction.render_mutex_.unlock();
    }

    void App::CompleteSelection(bool inverse) {
        reconstruction.render_mutex_.lock();
        Backup();
        selector.CompleteSelection(reconstruction.scene.static_meshes_, inverse);
        glm::vec3 center = selector.GetCenter(reconstruction.scene.static_meshes_);
        editor.SetCenter(center);
        reconstruction.scene.uniformPos = center;
        reconstruction.scene.UpdateSelected(false);
        reconstruction.render_mutex_.unlock();
    }

    void App::MultSelection(bool increase) {
        reconstruction.render_mutex_.lock();
        Backup();
        if (increase)
            selector.IncreaseSelection(reconstruction.scene.static_meshes_);
        else
            selector.DecreaseSelection(reconstruction.scene.static_meshes_);
        glm::vec3 center = selector.GetCenter(reconstruction.scene.static_meshes_);
        editor.SetCenter(center);
        reconstruction.scene.uniformPos = center;
        reconstruction.render_mutex_.unlock();
    }

    void App::CircleSelection(float x, float y, float radius, bool invert) {
        reconstruction.render_mutex_.lock();
        Backup();
        glm::mat4 matrix = reconstruction.scene.renderer->camera.projection * reconstruction.scene.renderer->camera.GetView();
        selector.SelectCircle(reconstruction.scene.static_meshes_, matrix, x, y, radius, invert);
        glm::vec3 center = selector.GetCenter(reconstruction.scene.static_meshes_);
        editor.SetCenter(center);
        reconstruction.scene.uniformPos = center;
        reconstruction.render_mutex_.unlock();
    }

    void App::RectSelection(float x1, float y1, float x2, float y2, bool invert) {
        reconstruction.render_mutex_.lock();
        Backup();
        glm::mat4 matrix = reconstruction.scene.renderer->camera.projection * reconstruction.scene.renderer->camera.GetView();
        selector.SelectRect(reconstruction.scene.static_meshes_, matrix, x1, y1, x2, y2, invert);
        glm::vec3 center = selector.GetCenter(reconstruction.scene.static_meshes_);
        editor.SetCenter(center);
        reconstruction.scene.uniformPos = center;
        reconstruction.render_mutex_.unlock();
    }

    void App::SetView(float p, float y, float mx, float my, float mz, float o, bool g) {
        orbit = o;
        pitch = p;
        yaw = y;
        gyro = g;
        movex = mx;
        movey = my;
        movez = mz;
        editor.SetPitch(pitch);
        reconstruction.scene.uniformPitch = pitch;
    }

    bool App::AnimFinished() {
        reconstruction.render_mutex_.lock();
        bool output = (fabs(lastPitch - pitch) < 0.01f) && (fabs(lastYaw - yaw) < 0.01f);
        reconstruction.render_mutex_.unlock();
        return output;
    }

    bool App::DidARJump() {
        bool output = reconstruction.jumped;
        reconstruction.jumped = false;
        return output;
    }

    float App::GetView(int axis) {
        reconstruction.render_mutex_.lock();
        float output = 0;
        if (axis == 0) output = lastMovex;
        if (axis == 1) output = lastMovey;
        if (axis == 2) output = lastMovez;
        reconstruction.render_mutex_.unlock();
        return output;
    }

    int App::GetScanSize() {
        reconstruction.render_mutex_.lock();
        int size = reconstruction.scan.Data().size();
        reconstruction.render_mutex_.unlock();
        return size;
    }

    void App::SetPhotoMode(bool on) {
        reconstruction.SetPhotoMode(on);
    }
}

std::string jbyteArray2string(JNIEnv* env, jbyteArray data)
{
    jbyte *jch = env->GetByteArrayElements( data, 0 );
    int size = env->GetArrayLength( data );
    std::string output;
    for( int i = 0; i < size; i++ )
        output.push_back( (unsigned char)jch[i] );

    env->ReleaseByteArrayElements(data,jch,0);

    return output;
}

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_onARServiceConnected(JNIEnv* env, jclass, jobject context,
        jdouble res, jdouble dmin, jdouble dmax, jint noise, jboolean holesFilling, jboolean poseCorr,
        jboolean distortion, jboolean offset, jboolean flashlight, jint mode, jboolean clearing, jbyteArray dataset) {
    return app.OnARServiceConnected(env, context, res, dmin, dmax, noise, holesFilling, poseCorr,
                                    distortion, offset, flashlight, mode, clearing, jbyteArray2string(env, dataset));
}

JNIEXPORT jint JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_getRuntimeCapabilities(JNIEnv*, jclass) {
    return app.GetRuntimeCapabilities();
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_onGlSurfaceChanged(
    JNIEnv*, jclass, jint width, jint height, jboolean fullhd) {
  app.OnSurfaceChanged(width, height, fullhd);
}

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_onGlSurfaceDrawFrame(JNIEnv*, jclass, jboolean facemode, jfloat yaw, jint viewmode, jboolean anchors, jboolean grid, jboolean smooth) {
  return app.OnDrawFrame(facemode, yaw, viewmode, anchors, grid, smooth);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_onToggleButtonClicked(
    JNIEnv*, jclass, jboolean t3dr_is_running) {
  app.OnToggleButtonClicked(t3dr_is_running);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_onClearButtonClicked(JNIEnv*, jclass) {
    app.OnClearButtonClicked();
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_onUndoButtonClicked(JNIEnv*, jclass, jboolean fromUser, jboolean texturize) {
    app.OnUndoButtonClicked(fromUser, texturize);
}


JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_onUndoPreviewUpdate(JNIEnv *env, jclass clazz, jint frames) {
    app.OnUndoPreviewUpdate(frames);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_onPause(JNIEnv*, jclass) {
    app.OnPause();
}

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_finalizeDatasetCapture(JNIEnv*, jclass) {
    return (jboolean) app.FinalizeDatasetCapture();
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_extract(JNIEnv* env, jclass, jbyteArray path, jint mode) {
    app.Extract(jbyteArray2string(env, path), mode);
}

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_load(JNIEnv* env, jclass, jbyteArray name) {
    return app.Load(jbyteArray2string(env, name));
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_optimize(JNIEnv* env, jclass, jbyteArray name) {
    app.Optimize(jbyteArray2string(env, name));
}

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_save(JNIEnv* env, jclass, jbyteArray name) {
    return (jboolean)app.Save(jbyteArray2string(env, name));
}

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_exportEngineering(
        JNIEnv* env, jclass, jbyteArray name, jint format) {
    return (jboolean) app.ExportEngineering(jbyteArray2string(env, name), format);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_saveWithTextures(JNIEnv* env, jclass, jbyteArray name) {
    app.SaveWithTextures(jbyteArray2string(env, name));
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_setTextureParams(JNIEnv*, jclass, jint detail,jint res, jint count) {
    app.SetTextureParams(detail, res, count);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_texturize(JNIEnv* env, jclass, jbyteArray input, jbyteArray output, jboolean poisson, jboolean twoPass) {
  app.Texturize(jbyteArray2string(env, input), jbyteArray2string(env, output), poisson, twoPass);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_setView(JNIEnv*, jclass, jfloat pitch, jfloat yaw,
                                                         jfloat x, jfloat y, jfloat z, jfloat o,
                                                         jboolean gyro) {
  app.SetView(pitch, yaw, x, y, z, o, gyro);
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_getDistance(JNIEnv*, jclass, jfloat x1,
                                                       jfloat y1, jfloat x2, jfloat y2) {
    return app.GetDistance(x1, y1, x2, y2);
}

JNIEXPORT jfloatArray JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_pickMeasurementPoint(
        JNIEnv* env, jclass, jfloat x, jfloat y) {
    std::vector<float> values = app.PickMeasurementPoint(x, y);
    jfloatArray output = env->NewFloatArray((jsize) values.size());
    if (!values.empty())
        env->SetFloatArrayRegion(output, 0, (jsize) values.size(), values.data());
    return output;
}

JNIEXPORT jfloatArray JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_projectMeasurementPoints(
        JNIEnv* env, jclass, jfloatArray input) {
    jsize size = env->GetArrayLength(input);
    std::vector<float> values((unsigned int) size);
    if (size > 0)
        env->GetFloatArrayRegion(input, 0, size, values.data());
    std::vector<float> projected = app.ProjectMeasurementPoints(values);
    jfloatArray output = env->NewFloatArray((jsize) projected.size());
    if (!projected.empty())
        env->SetFloatArrayRegion(output, 0, (jsize) projected.size(), projected.data());
    return output;
}

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_applyUniformScale(
        JNIEnv*, jclass, jfloat factor, jfloat x, jfloat y, jfloat z) {
    return (jboolean) app.ApplyUniformScale(factor, x, y, z);
}

JNIEXPORT jfloat JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_getFloorLevel(JNIEnv*, jclass, jfloat x, jfloat y, jfloat z) {
    return app.GetFloorLevel(x, y, z);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_restore(JNIEnv*, jclass) {
    app.Restore();
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_applyEffect(JNIEnv*, jclass, jint effect, jfloat value, jint axis) {
    app.ApplyEffect((oc::Effector::Effect) effect, value, axis);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_previewEffect(JNIEnv*, jclass, jint effect, jfloat value, jint axis) {
    app.PreviewEffect((oc::Effector::Effect) effect, value, axis);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_applySelect(JNIEnv*, jclass, jfloat x, jfloat y, jboolean triangle) {
    app.ApplySelection(x, y, triangle);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_completeSelection(JNIEnv*, jclass, jboolean inverse) {
    app.CompleteSelection(inverse);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_multSelection(JNIEnv*, jclass, jboolean increase) {
    app.MultSelection(increase);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_circleSelection(JNIEnv*, jclass, jfloat x, jfloat y,
                                                           jfloat radius, jboolean invert) {
    app.CircleSelection(x, y, radius, invert);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_rectSelection(JNIEnv*, jclass, jfloat x1, jfloat y1,
                                                         jfloat x2, jfloat y2, jboolean invert) {
    app.RectSelection(x1, y1, x2, y2, invert);
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_showNormals(JNIEnv*, jclass, jboolean on) {
    app.ShowNormals(on);
}

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_animFinished(JNIEnv*, jclass) {
    return (jboolean) app.AnimFinished();
}

JNIEXPORT jboolean JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_didARjump(JNIEnv*, jclass) {
    return (jboolean) app.DidARJump();
}

JNIEXPORT jfloat JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_getView(JNIEnv*, jclass, jint axis) {
    return app.GetView(axis);
}

JNIEXPORT jbyteArray JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_getEvent(JNIEnv* env, jclass) {
  std::string message = app.GetEvent();
  int byteCount = (int) message.length();
  const jbyte* pNativeMessage = reinterpret_cast<const jbyte*>(message.c_str());
  jbyteArray bytes = env->NewByteArray(byteCount);
  env->SetByteArrayRegion(bytes, 0, byteCount, pNativeMessage);
  return bytes;
}

JNIEXPORT void JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_setPhotoMode(JNIEnv *env, jclass clazz, jboolean on) {
    app.SetPhotoMode(on);
}

JNIEXPORT jint JNICALL
Java_com_lvonasek_arcore3dscanner_main_JNI_getScanSize(JNIEnv *env, jclass clazz) {
    return app.GetScanSize();
}

#ifdef __cplusplus
}
#endif
