#include <arcore/arcore.h>
#include <cstring>
#include <mutex>
#include "service.h"

namespace oc {

    namespace {

        bool HasActiveDepthSensor(ArSession* session) {
            if (!session) {
                return false;
            }

            ArCameraConfig* cameraConfig = nullptr;
            ArCameraConfig_create(session, &cameraConfig);
            if (!cameraConfig) {
                return false;
            }

            ArSession_getCameraConfig(session, cameraConfig);
            uint32_t usage = 0;
            ArCameraConfig_getDepthSensorUsage(session, cameraConfig, &usage);
            ArCameraConfig_destroy(cameraConfig);
            return (usage & AR_CAMERA_CONFIG_DEPTH_SENSOR_USAGE_REQUIRE_AND_USE) != 0;
        }

        bool SelectActiveDepthCamera(ArSession* session) {
            if (!session) return false;

            ArCameraConfigFilter* filter = nullptr;
            ArCameraConfigList* list = nullptr;
            ArCameraConfig* config = nullptr;
            ArCameraConfigFilter_create(session, &filter);
            ArCameraConfigList_create(session, &list);
            ArCameraConfig_create(session, &config);
            if (!filter || !list || !config) {
                if (config) ArCameraConfig_destroy(config);
                if (list) ArCameraConfigList_destroy(list);
                if (filter) ArCameraConfigFilter_destroy(filter);
                return false;
            }

            ArCameraConfigFilter_setDepthSensorUsage(
                session, filter, AR_CAMERA_CONFIG_DEPTH_SENSOR_USAGE_REQUIRE_AND_USE);
            ArCameraConfigFilter_setFacingDirection(
                session, filter, AR_CAMERA_CONFIG_FACING_DIRECTION_BACK);
            ArSession_getSupportedCameraConfigsWithFilter(session, filter, list);
            int32_t count = 0;
            ArCameraConfigList_getSize(session, list, &count);
            bool selected = false;
            if (count > 0) {
                ArCameraConfigList_getItem(session, list, 0, config);
                selected = ArSession_setCameraConfig(session, config) == AR_SUCCESS;
            }

            ArCameraConfig_destroy(config);
            ArCameraConfigList_destroy(list);
            ArCameraConfigFilter_destroy(filter);
            return selected;
        }

        uint16_t ReadDepthPixel(const uint8_t* data, int32_t rowStride,
                                int32_t pixelStride, int x, int y) {
            uint16_t value = 0;
            std::memcpy(&value, data + y * rowStride + x * pixelStride,
                        sizeof(value));
            return value;
        }

        uint8_t ReadConfidencePixel(const uint8_t* data, int32_t rowStride,
                                    int32_t pixelStride, int x, int y) {
            return data[y * rowStride + x * pixelStride];
        }

    }

    ARCore::ARCore(void *env, void *context, bool faceMode, bool depthCamera) {
        useDepth = false;
        useDepthRaw = false;
        has_depth_sensor = false;
#ifndef ARCORE_BACKPORT
        if (env && context) {
            ArStatus sessionStatus = AR_ERROR_FATAL;
            if (faceMode) {
                ArSessionFeature features[2] = {AR_SESSION_FEATURE_FRONT_CAMERA, AR_SESSION_FEATURE_END_OF_LIST};
                sessionStatus = ArSession_createWithFeatures(env, context, features, &ar_session_);
            } else {
                sessionStatus = ArSession_create(env, context, &ar_session_);
            }

            if ((sessionStatus == AR_SUCCESS) && ar_session_) {
                const bool selectedHardwareDepth =
                    !faceMode && depthCamera && SelectActiveDepthCamera(ar_session_);
                ArConfig *ar_config = nullptr;
                ArConfig_create(ar_session_, &ar_config);
                ArConfig_setFocusMode(ar_session_, ar_config, AR_FOCUS_MODE_AUTO);
                ArConfig_setPlaneFindingMode(ar_session_, ar_config, AR_PLANE_FINDING_MODE_DISABLED);
                if (faceMode) {
                    ArConfig_setAugmentedFaceMode(ar_session_, ar_config, AR_AUGMENTED_FACE_MODE_MESH3D);
                } else {
                    int32_t rawDepthSupported = 0;
                    int32_t automaticDepthSupported = 0;
                    ArSession_isDepthModeSupported(ar_session_, AR_DEPTH_MODE_RAW_DEPTH_ONLY,
                                                   &rawDepthSupported);
                    ArSession_isDepthModeSupported(ar_session_, AR_DEPTH_MODE_AUTOMATIC,
                                                   &automaticDepthSupported);

                    if (selectedHardwareDepth && rawDepthSupported) {
                        ArConfig_setDepthMode(ar_session_, ar_config, AR_DEPTH_MODE_RAW_DEPTH_ONLY);
                        useDepth = true;
                        useDepthRaw = true;
                    } else if (automaticDepthSupported) {
                        // AUTOMATIC is dense and temporally smoothed, which is
                        // the best input for meshing on phones without a depth
                        // camera exposed through ARCore.
                        ArConfig_setDepthMode(ar_session_, ar_config, AR_DEPTH_MODE_AUTOMATIC);
                        useDepth = true;
                        useDepthRaw = false;
                    } else if (rawDepthSupported) {
                        ArConfig_setDepthMode(ar_session_, ar_config, AR_DEPTH_MODE_RAW_DEPTH_ONLY);
                        useDepth = true;
                        useDepthRaw = true;
                    }
                }

                ArConfig_setUpdateMode(ar_session_, ar_config, AR_UPDATE_MODE_BLOCKING);
                ArStatus configureStatus = ArSession_configure(ar_session_, ar_config);
                if ((configureStatus != AR_SUCCESS) && useDepth) {
                    useDepth = false;
                    useDepthRaw = false;
                    ArConfig_setDepthMode(ar_session_, ar_config, AR_DEPTH_MODE_DISABLED);
                    configureStatus = ArSession_configure(ar_session_, ar_config);
                }
                ArConfig_destroy(ar_config);

                if (configureStatus == AR_SUCCESS) {
                    ArFrame_create(ar_session_, &ar_frame_);
                    session_ready_ = ar_frame_ != nullptr;
                    has_depth_sensor = useDepth && selectedHardwareDepth &&
                                       HasActiveDepthSensor(ar_session_);
                }
            }
        } else
#endif
        {
            useDepth = depthCamera;
            useDepthRaw = depthCamera;
        }

        ar_zero_.second = 0;
        has_coordinate_system_ = false;
        lastDepthTimestamp = 0;
        offset = 0;
        resolution = 0;
        depth_min = 0.05f;
        depth_max = 100.0f;
        face_mode_ = faceMode;
    }

    ARCore::~ARCore() {
        if (ar_frame_) {
            ArFrame_destroy(ar_frame_);
            ar_frame_ = nullptr;
        }
        if (ar_session_) {
            ArSession_destroy(ar_session_);
            ar_session_ = nullptr;
        }
    }

    void ARCore::Clear(bool detach) {
        if (detach) {
            for (auto& anchor : ar_anchor_list) {
                ArAnchor_detach(ar_session_, ar_anchor_list[anchor.first]);
                ArAnchor_release(ar_anchor_list[anchor.first]);
            }
            ArAnchor_detach(ar_session_, ar_zero_.second);
            ArAnchor_release(ar_zero_.second);
        }
        ar_anchor_list.clear();
        ar_zero_.second = 0;
        has_coordinate_system_ = false;
    }

    void ARCore::OnPause() {
        if (session_ready_)
            ArSession_pause(ar_session_);
    }

    void ARCore::OnResume() {
        if (!session_ready_ || (ArSession_resume(ar_session_) != AR_SUCCESS))
            return;
        camera.InitializeGlContent();
        texture_initialized_ = false;
    }

    void ARCore::OnDisplayGeometryChanged(int display_rotation, int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        if (session_ready_)
            ArSession_setDisplayGeometry(ar_session_, display_rotation, width, height);
    }

    void ARCore::Configure(void *session, void *frame) {
        ar_session_ = static_cast<ArSession *>(session);
        ar_frame_ = static_cast<ArFrame *>(frame);
        session_ready_ = (ar_session_ != nullptr) && (ar_frame_ != nullptr);

        if (session_ready_) {
            int32_t rawDepthSupported = 0;
            int32_t automaticDepthSupported = 0;
            ArSession_isDepthModeSupported(ar_session_, AR_DEPTH_MODE_RAW_DEPTH_ONLY,
                                           &rawDepthSupported);
            ArSession_isDepthModeSupported(ar_session_, AR_DEPTH_MODE_AUTOMATIC,
                                           &automaticDepthSupported);
            useDepth = useDepth && (rawDepthSupported || automaticDepthSupported);
            useDepthRaw = useDepth && rawDepthSupported;
            has_depth_sensor = useDepth && HasActiveDepthSensor(ar_session_);
        } else {
            useDepth = false;
            useDepthRaw = false;
            has_depth_sensor = false;
        }
    }

    float ARCore::CountFrameError() {
        int size = 0;
        float error = 10000;
        float data[7] = {0, 0, 0, 1, 0, 0, 0};
        ArPose *ar_pose;
        ArPose_create(ar_session_, data, &ar_pose);
        glm::mat4 matrix = projection_mat * view_mat;

        ArHitResult* hit = 0;
        ArHitResultList* hits = 0;
        ArHitResult_create(ar_session_, &hit);
        ArHitResultList_create(ar_session_, &hits);
        for (glm::vec3& v : GetActiveAnchors()) {
            glm::vec4 point = matrix * glm::vec4(v, 1.0);
            point /= fabs(point.z * point.w);
            point = 0.5f * point + 0.5f;

            ArFrame_hitTest(ar_session_, ar_frame_, viewportWidth * point.x, viewportHeight * point.y, hits);
            ArHitResultList_getSize(ar_session_, hits, &size);
            for (int i = 0; i < size; i++) {
                ArTrackable *trackable = 0;
                ArHitResultList_getItem(ar_session_, hits, i, hit);
                ArHitResult_acquireTrackable(ar_session_, hit, &trackable);
                ArPoint_getPose(ar_session_, ArAsPoint(trackable), ar_pose);
                ArPose_getPoseRaw(ar_session_, ar_pose, data);
                glm::vec3 position = glm::vec3(data[4], data[5], data[6]);
                float dst = glm::distance(v, position);
                if (dst > 0) {
                    error = glm::min(error, dst);
                }
                ArTrackable_release(trackable);
            }
        }

        ArHitResultList_destroy(hits);
        ArHitResult_destroy(hit);
        ArPose_destroy(ar_pose);
        return error;
    }

    bool ARCore::Process(bool update) {
        if (!session_ready_)
            return false;
        if (update) {
            if (!texture_initialized_) {
                ArSession_setCameraTextureName(ar_session_, camera.GetTextureName());
                texture_initialized_ = true;
            }
            if (ArSession_update(ar_session_, ar_frame_) != AR_SUCCESS)
                return false;
        }

        ArCamera *ar_camera;
        ArFrame_acquireCamera(ar_session_, ar_frame_, &ar_camera);
        ArCamera_getViewMatrix(ar_session_, ar_camera, glm::value_ptr(view_mat));
        ArCamera_getProjectionMatrix(ar_session_, ar_camera, 0.001f, 100.f,
                                       glm::value_ptr(projection_mat));
        view_mat = view_mat * GetZeroTransform();
        ArCamera_release(ar_camera);

        if (face_mode_) {
            return true;
        } else if (ar_anchor_list.empty()) {
            return UpdateAnchor();
        } else {
            return true;
        }
    }

    void ARCore::RenderCamera(ARCoreCamera::Effect effect, int scale) {
        if (effect >= ARCoreCamera::DEPTH) {
            Image* img = 0;
            if (effect == ARCoreCamera::NIGHTVISION)
                img = GetDepthMap(true, false, scale);
            else if (effect == ARCoreCamera::DEPTH_INV)
                img = GetDepthMap(false, false, scale);
            else
                img = GetDepthMap(false, true, scale);
            if (img) {
                GLuint texture = GLSL::Image2GLTexture(img);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texture);
                camera.GetShader()->Bind();
                camera.GetShader()->UniformInt("depth", 0);
                camera.DrawARCore(ar_session_, ar_frame_, effect, viewportWidth, viewportHeight);
                glDeleteTextures(1, &texture);
                delete img;
            }
        } else {
            camera.DrawARCore(ar_session_, ar_frame_, effect, viewportWidth, viewportHeight);
        }
    }

    std::vector<glm::vec3> ARCore::GetActiveAnchors() {
        float data[7] = {0, 0, 0, 1, 0, 0, 0};
        ArPose *ar_pose;
        ArPose_create(ar_session_, data, &ar_pose);
        std::vector<glm::vec3> output;

        glm::mat4 zero = glm::inverse(GetZeroTransform());
        for (auto& anchor : ar_anchor_list) {
            ArTrackingState state = AR_TRACKING_STATE_STOPPED;
            ArAnchor_getTrackingState(ar_session_, anchor.second, &state);
            if (state == AR_TRACKING_STATE_TRACKING) {
                ArAnchor_getPose(ar_session_, anchor.second, ar_pose);
                ArPose_getPoseRaw(ar_session_, ar_pose, data);
                glm::vec3 v = glm::vec3(data[4], data[5], data[6]);
                glm::vec4 p = zero * glm::vec4(v, 1.0f);
                v = p / fabs(p.w);
                output.push_back(v);
            }
        }
        ArPose_destroy(ar_pose);
        return output;
    }

    std::vector<float> ARCore::GetDistortion() {
        std::vector<float> output;
        for (int i = 0; i < 3; i++) {
            output.push_back(0);
        }
        return output;
    }

    glm::vec3 ARCore::HitTest(int x, int y) {
        float data[7] = {0, 0, 0, 1, 0, 0, 0};
        ArPose *ar_pose;
        ArPose_create(ar_session_, data, &ar_pose);

        int size = 0;
        ArHitResult* hit = 0;
        ArHitResultList* hits = 0;
        ArHitResult_create(ar_session_, &hit);
        ArHitResultList_create(ar_session_, &hits);
        ArFrame_hitTest(ar_session_, ar_frame_, x, y, hits);
        ArHitResultList_getSize(ar_session_, hits, &size);
        if (size > 0) {
            ArTrackable* trackable = 0;
            ArHitResultList_getItem(ar_session_, hits, 0, hit);
            ArHitResult_acquireTrackable(ar_session_, hit, &trackable);
            ArPoint_getPose(ar_session_, ArAsPoint(trackable), ar_pose);
            ArPose_getPoseRaw(ar_session_, ar_pose, data);
            ArTrackable_release(trackable);
            ArHitResultList_destroy(hits);
            ArHitResult_destroy(hit);
            ArPose_destroy(ar_pose);
            return glm::vec3(data[4], data[5], data[6]);
        }
        ArHitResultList_destroy(hits);
        ArHitResult_destroy(hit);
        ArPose_destroy(ar_pose);
        return glm::vec3(INT_MAX);
    }

    Image* ARCore::GetDepthMap(bool confidence, bool increasing, int s) {

        if (useDepth && session_ready_) {
            ArImage* image = 0;
            int result = 0;
            if (useDepthRaw) {
                result = ArFrame_acquireRawDepthImage16Bits(ar_session_, ar_frame_, &image);
            } else {
                result = ArFrame_acquireDepthImage16Bits(ar_session_, ar_frame_, &image);
            }

            if (result == AR_SUCCESS) {

                const uint8_t* confidenceData = nullptr;
                ArImage* confidenceImage = 0;
                bool hasConfidence = false;
                int32_t confidenceWidth = 0, confidenceHeight = 0;
                int32_t confidenceRowStride = 0, confidencePixelStride = 0;
                if (useDepthRaw) {
                    result = ArFrame_acquireRawDepthConfidenceImage(ar_session_, ar_frame_, &confidenceImage);
                    if (result == AR_SUCCESS) {
                        int32_t confidenceDataLength;
                        ArImage_getPlaneData(ar_session_, confidenceImage, 0, &confidenceData,
                                             &confidenceDataLength);
                        ArImage_getWidth(ar_session_, confidenceImage, &confidenceWidth);
                        ArImage_getHeight(ar_session_, confidenceImage, &confidenceHeight);
                        ArImage_getPlaneRowStride(ar_session_, confidenceImage, 0,
                                                  &confidenceRowStride);
                        ArImage_getPlanePixelStride(ar_session_, confidenceImage, 0,
                                                    &confidencePixelStride);
                        hasConfidence = confidenceData && (confidenceDataLength > 0) &&
                                        (confidenceRowStride > 0) && (confidencePixelStride > 0);
                    }
                }

                //get depth data
                const uint8_t* imgData = nullptr;
                int32_t dataLength;
                int32_t depthWidth, depthHeight, rowStride, pixelStride;
                ArImage_getPlaneRowStride(ar_session_, image, 0, &rowStride);
                ArImage_getPlanePixelStride(ar_session_, image, 0, &pixelStride);
                ArImage_getPlaneData(ar_session_, image, 0, &imgData, &dataLength);
                ArImage_getWidth(ar_session_, image, &depthWidth);
                ArImage_getHeight(ar_session_, image, &depthHeight);

                if (!imgData || (dataLength <= 0) || (rowStride <= 0) ||
                    (pixelStride < static_cast<int32_t>(sizeof(uint16_t)))) {
                    if (confidenceImage)
                        ArImage_release(confidenceImage);
                    ArImage_release(image);
                    return nullptr;
                }

                hasConfidence = hasConfidence && (confidenceWidth == depthWidth) &&
                                (confidenceHeight == depthHeight);
                int sampleStep = s > 0 ? s : 1;
                if (depthWidth > 240)
                    sampleStep *= glm::max(1, depthWidth / 240);
                const int outputWidth = glm::max(1, depthWidth / sampleStep);
                const int outputHeight = glm::max(1, depthHeight / sampleStep);
                Image* output = new Image(outputWidth, outputHeight);
                for (int y = 0; y < outputHeight; y++) {
                    for (int x = 0; x < outputWidth; x++) {
                        const int sourceX = sampleStep * x;
                        const int sourceY = sampleStep * y;
                        int depth = static_cast<int>(ReadDepthPixel(imgData, rowStride, pixelStride,
                                                                    sourceX, sourceY) * 0.001 * 255);
                        if (!increasing && depth > 0) depth = 768 - depth;
                        output->GetData()[(y * outputWidth + x) * 4 + 0] = camera.Convert(depth, 0);
                        output->GetData()[(y * outputWidth + x) * 4 + 1] = camera.Convert(depth, 1);
                        output->GetData()[(y * outputWidth + x) * 4 + 2] = camera.Convert(depth, 2);
                        output->GetData()[(y * outputWidth + x) * 4 + 3] = 255;
                        if (confidence && hasConfidence) {
                            output->GetData()[(y * outputWidth + x) * 4 + 3] =
                                128 + ReadConfidencePixel(confidenceData, confidenceRowStride,
                                                          confidencePixelStride, sourceX, sourceY) / 2;
                        }
                    }
                }

                if (confidenceImage) {
                    ArImage_release(confidenceImage);
                }
                ArImage_release(image);
                return output;
            }
        }
        return 0;
    }

    glm::mat4 ARCore::GetMatrix(ArPose* ar_pose) {
        float* matrix = new float[16];
        ArPose_getMatrix(ar_session_, ar_pose, matrix);

        glm::mat4 output(1);
        output[0][0] = matrix[0];
        output[0][1] = matrix[1];
        output[0][2] = matrix[2];
        output[0][3] = matrix[3];
        output[1][0] = matrix[4];
        output[1][1] = matrix[5];
        output[1][2] = matrix[6];
        output[1][3] = matrix[7];
        output[2][0] = matrix[8];
        output[2][1] = matrix[9];
        output[2][2] = matrix[10];
        output[2][3] = matrix[11];
        output[3][0] = matrix[12];
        output[3][1] = matrix[13];
        output[3][2] = matrix[14];
        output[3][3] = matrix[15];
        delete[] matrix;
        return output;
    }

    glm::mat4 ARCore::GetZeroTransform() {
#ifndef ARCORE_BACKPORT
        if (ar_zero_.second) {
            float data[7] = {0, 0, 0, 1, 0, 0, 0};

            ArPose *ar_pose;
            ArPose_create(ar_session_, data, &ar_pose);
            ArAnchor_getPose(ar_session_, ar_zero_.second, ar_pose);
            glm::mat4 matrix = GetMatrix(ar_pose);
            ArPose_destroy(ar_pose);

            return matrix * glm::inverse(ar_zero_.first.matrix);
        }
#endif
        return glm::mat4(1);
    }

    glm::vec4 ARCore::ToPoint(glm::dmat4& screen2world, double& len,
                      int32_t& depthWidth, int32_t& depthHeight, int& x, int& y, double& depth) {

        //convert sensor coordinates to screen coordinates
        glm::dvec2 T = camera.Transform(x, y, depthWidth, depthHeight);

        //create a ray from screen space to world space
        glm::dvec4 point0 = screen2world * glm::vec4(T, 0, 1);
        point0 /= glm::abs(point0.w);
        point0.w = 1;
        glm::dvec4 point1 = screen2world * glm::vec4(T, 1, 1);
        point1 /= glm::abs(point1.w);
        point1.w = 1;

        //get a point on ray that match the depth
        return point0 + (point1 - point0) / len * depth;
    }

    bool ARCore::UpdateAnchor() {
        if (!ar_anchor_list.empty() && GetActiveAnchors().empty()) {
            return false;
        }

        float data[7] = {0, 0, 0, 1, 0, 0, 0};
        ArPose *ar_pose;
        ArPose_create(ar_session_, data, &ar_pose);

        bool valid = false;
        ArAnchor* ar_anchor_ = 0;
        int size = 0;
        ArHitResult* hit = 0;
        ArHitResultList* hits = 0;
        ArHitResult_create(ar_session_, &hit);
        ArHitResultList_create(ar_session_, &hits);
        for (float x = 0.25f; x <= 0.75f; x += 0.5f) {
            for (float y = 0.25f; y <= 0.75f; y += 0.5f) {
                ArFrame_hitTest(ar_session_, ar_frame_, viewportWidth * x, viewportHeight * y, hits);
                ArHitResultList_getSize(ar_session_, hits, &size);
                for (int i = 0; i < size; i++) {
                    ArTrackable* trackable = 0;
                    ArHitResultList_getItem(ar_session_, hits, i, hit);
                    ArHitResult_acquireTrackable(ar_session_, hit, &trackable);
                    ArTrackableType type = AR_TRACKABLE_NOT_VALID;
                    ArTrackable_getType(ar_session_, trackable, &type);
                    if (type == AR_TRACKABLE_POINT) {

                        ArPoint_getPose(ar_session_, ArAsPoint(trackable), ar_pose);
                        ArPose_getPoseRaw(ar_session_, ar_pose, data);
                        glm::vec3 position(data[4], data[5], data[6]);

                        id3d pos;
                        pos.matrix = GetMatrix(ar_pose);
                        float density = ANCHOR_DENSITY_BASE;
                        for (pos.layer = 0; pos.layer < ANCHOR_LAYERS; pos.layer++) {
                            pos.x = static_cast<int>(position.x / density);
                            pos.y = static_cast<int>(position.y / density);
                            pos.z = static_cast<int>(position.z / density);
                            valid = true;
                            if (ar_anchor_list.find(pos) == ar_anchor_list.end()) {
                                ArStatus ret = ArTrackable_acquireNewAnchor(ar_session_, trackable, ar_pose, &ar_anchor_);
                                if (ret == AR_SUCCESS) {
                                    if (ar_zero_.second == 0) {
                                        ar_zero_.first = pos;
                                        ar_zero_.second = ar_anchor_;
                                        break;
                                    }

                                    ar_anchor_list[pos] = ar_anchor_;
                                    while (true) {
                                        int count = 0;
                                        id3d far = pos;
                                        for (auto& anchor : ar_anchor_list) {
                                            if (anchor.first.layer == pos.layer) {
                                                if (Diff(anchor.first, pos) > Diff(far, pos)) {
                                                    far = anchor.first;
                                                }
                                                count++;
                                            }
                                        }
                                        if (count > ANCHOR_CACHE) {
                                            ArAnchor_detach(ar_session_, ar_anchor_list[far]);
                                            ArAnchor_release(ar_anchor_list[far]);
                                            ar_anchor_list.erase(far);
                                        } else {
                                            break;
                                        }
                                    }
                                }
                            }
                            density *= ANCHOR_DENSITY_SCALE;
                        }
                    }
                    ArTrackable_release(trackable);
                }
            }
        }
        ArHitResultList_destroy(hits);
        ArHitResult_destroy(hit);
        ArPose_destroy(ar_pose);

        if (!valid && !GetActiveAnchors().empty()) {
            valid = true;
        }
        return valid;
    }

    void ARCore::UpdateFace(glm::mat4 matrix) {
#ifndef ARCORE_BACKPORT
        face_mesh.vertices.clear();
        face_mesh.normals.clear();
        face_mesh.uv.clear();
        face_mesh.indices.clear();
        points.clear();
        int32_t size = 0;
        ArTrackableList* faces = 0;
        ArTrackableList_create(ar_session_, &faces);
        ArSession_getAllTrackables(ar_session_, AR_TRACKABLE_FACE, faces);
        ArTrackableList_getSize(ar_session_, faces, &size);
        for (int32_t i = 0; i < size; i++) {
            int32_t count = 0;
            const float* vertices = 0;
            const float* normals = 0;
            ArTrackable* face = 0;
            ArTrackableList_acquireItem(ar_session_, faces, i, &face);
            ArAugmentedFace_getMeshVertices(ar_session_, ArAsFace(face), &vertices, &count);
            ArAugmentedFace_getMeshNormals(ar_session_, ArAsFace(face), &normals, &count);

            float data[7] = {0, 0, 0, 1, 0, 0, 0};
            ArPose *ar_pose;
            ArPose_create(ar_session_, data, &ar_pose);
            ArAugmentedFace_getCenterPose(ar_session_, ArAsFace(face), ar_pose);
            ArPose_getPoseRaw(ar_session_, ar_pose, data);

            GLCamera pose;
            pose.position = glm::vec3(data[4], data[5], data[6]);
            pose.rotation = glm::quat(data[3], data[0], data[1], data[2]);
            pose.scale = glm::vec3(1);
            glm::mat4 transform = pose.GetTransformation();
            for (int j = 0; j < count; j++) {
                glm::vec4 point = glm::vec4(vertices[j * 3 + 0],
                                            vertices[j * 3 + 1],
                                            vertices[j * 3 + 2],
                                            1.0f);
                point = transform * point;
                point /= fabs(point.w);
                point.w = 1.0f;
                face_mesh.vertices.push_back(point);
                face_mesh.normals.push_back(glm::vec3(normals[j * 3 + 0],
                                                      normals[j * 3 + 1],
                                                      normals[j * 3 + 2]));

                point = matrix * point;
                point /= fabs(point.w);
                face_mesh.uv.push_back(0.5f * glm::vec2(point.x, point.y) + 0.5f);
            }

            const uint16_t* indices = 0;
            int32_t triangles = 0;
            ArAugmentedFace_getMeshTriangleIndices(ar_session_, ArAsFace(face), &indices, &triangles);
            for (int j = 0; j < triangles; j++) {
                if (face_not_all) {
                    bool ok = true;
                    for (int l = j * 3 + 0; l < j * 3 + 3; l++)
                    {
                        if ((indices[l] == 13) || (indices[l] == 14))
                            ok = false;
                        if ((indices[l] == 78) || (indices[l] == 95))
                            ok = false;
                        if ((indices[l] >= 80) && (indices[l] <= 82))
                            ok = false;
                        if ((indices[l] == 87) || (indices[l] == 88))
                            ok = false;
                        if ((indices[l] == 178) || (indices[l] == 191))
                            ok = false;
                        if ((indices[l] == 308) || (indices[l] == 324))
                            ok = false;
                        if ((indices[l] >= 310) && (indices[l] <= 312))
                            ok = false;
                        if ((indices[l] == 317) || (indices[l] == 318))
                            ok = false;
                        if ((indices[l] == 402) || (indices[l] == 415))
                            ok = false;
                    }
                    if (!ok)
                        continue;
                }
                face_mesh.indices.push_back(indices[j * 3 + 0]);
                face_mesh.indices.push_back(indices[j * 3 + 1]);
                face_mesh.indices.push_back(indices[j * 3 + 2]);
                points.push_back(glm::vec4(face_mesh.vertices[indices[j * 3 + 0]], 1.0f));
                points.push_back(glm::vec4(face_mesh.vertices[indices[j * 3 + 1]], 1.0f));
                points.push_back(glm::vec4(face_mesh.vertices[indices[j * 3 + 1]], 1.0f));
                points.push_back(glm::vec4(face_mesh.vertices[indices[j * 3 + 2]], 1.0f));
                points.push_back(glm::vec4(face_mesh.vertices[indices[j * 3 + 2]], 1.0f));
                points.push_back(glm::vec4(face_mesh.vertices[indices[j * 3 + 0]], 1.0f));
            }

            ArPose_destroy(ar_pose);
            ArTrackable_release(face);
        }
        ArTrackableList_destroy(faces);
#endif
    }

    void ARCore::UpdateFeaturePoints() {
        if (!session_ready_) {
            points.clear();
            return;
        }
        if (!UpdateAnchor()) {
            points.clear();
            return;
        }

        // Start every frame from a fresh feature cloud. A fresh dense depth
        // image replaces it below; otherwise these points keep scanning alive
        // while ARCore is warming up or reprojecting an unchanged depth frame.
        points.clear();
        depth_telemetry.feature_points = 0;

        ArPointCloud *ar_point_cloud = nullptr;
        ArStatus point_cloud_status = ArFrame_acquirePointCloud(ar_session_, ar_frame_, &ar_point_cloud);
        int32_t number_of_points = 0;
        if (point_cloud_status == AR_SUCCESS) {
            ArPointCloud_getNumberOfPoints(ar_session_, ar_point_cloud, &number_of_points);
            const float *point_cloud_data;
            ArPointCloud_getData(ar_session_, ar_point_cloud, &point_cloud_data);
            for (int i = 0; i < number_of_points * 4; i += 4) {
                points.push_back(glm::vec4(point_cloud_data[i + 0], point_cloud_data[i + 1],
                                           point_cloud_data[i + 2], point_cloud_data[i + 3]));
            }
            depth_telemetry.feature_points = number_of_points;
            ArPointCloud_release(ar_point_cloud);
        }

        if (useDepth) {
            camera.InitARCore(ar_session_, ar_frame_);

                ArImage* image = 0;
                int result = 0;
                if (useDepthRaw) {
                    result = ArFrame_acquireRawDepthImage16Bits(ar_session_, ar_frame_, &image);
                } else {
                    result = ArFrame_acquireDepthImage16Bits(ar_session_, ar_frame_, &image);
                }
                depth_telemetry.acquire_status = result;
                if (result == AR_SUCCESS) {

                    const uint8_t* confidenceData = nullptr;
                    ArImage* confidenceImage = 0;
                    bool hasConfidence = false;
                    int32_t confidenceWidth = 0, confidenceHeight = 0;
                    int32_t confidenceRowStride = 0, confidencePixelStride = 0;
                    if (useDepthRaw) {
                        result = ArFrame_acquireRawDepthConfidenceImage(ar_session_, ar_frame_, &confidenceImage);
                        if (result == AR_SUCCESS) {
                            int32_t confidenceDataLength;
                            ArImage_getPlaneData(ar_session_, confidenceImage, 0, &confidenceData,
                                                 &confidenceDataLength);
                            ArImage_getWidth(ar_session_, confidenceImage, &confidenceWidth);
                            ArImage_getHeight(ar_session_, confidenceImage, &confidenceHeight);
                            ArImage_getPlaneRowStride(ar_session_, confidenceImage, 0,
                                                      &confidenceRowStride);
                            ArImage_getPlanePixelStride(ar_session_, confidenceImage, 0,
                                                        &confidencePixelStride);
                            hasConfidence = confidenceData && (confidenceDataLength > 0) &&
                                            (confidenceRowStride > 0) &&
                                            (confidencePixelStride > 0);
                        }
                    }

                    //get depth data
                    const uint8_t* imgData = nullptr;
                    int64_t timestamp;
                    int32_t dataLength;
                    int32_t depthWidth, depthHeight, rowStride, pixelStride;
                    ArImage_getPlaneRowStride(ar_session_, image, 0, &rowStride);
                    ArImage_getPlanePixelStride(ar_session_, image, 0, &pixelStride);
                    ArImage_getPlaneData(ar_session_, image, 0, &imgData, &dataLength);
                    ArImage_getWidth(ar_session_, image, &depthWidth);
                    ArImage_getHeight(ar_session_, image, &depthHeight);
                    ArImage_getTimestamp(ar_session_, image, &timestamp);

                    hasConfidence = hasConfidence && (confidenceWidth == depthWidth) &&
                                    (confidenceHeight == depthHeight);

                    ArImage* image2 = 0;
                    const uint8_t* imgData2 = nullptr;
                    int32_t secondaryWidth = 0, secondaryHeight = 0;
                    int32_t secondaryRowStride = 0, secondaryPixelStride = 0;
                    bool hasSecondary = false;
                    if (useDepthRaw && !has_depth_sensor) {
                        result = ArFrame_acquireDepthImage16Bits(ar_session_, ar_frame_, &image2);
                        if (result == AR_SUCCESS) {
                            int32_t secondaryDataLength;
                            ArImage_getPlaneData(ar_session_, image2, 0, &imgData2,
                                                 &secondaryDataLength);
                            ArImage_getWidth(ar_session_, image2, &secondaryWidth);
                            ArImage_getHeight(ar_session_, image2, &secondaryHeight);
                            ArImage_getPlaneRowStride(ar_session_, image2, 0,
                                                      &secondaryRowStride);
                            ArImage_getPlanePixelStride(ar_session_, image2, 0,
                                                        &secondaryPixelStride);
                            hasSecondary = imgData2 && (secondaryDataLength > 0) &&
                                           (secondaryWidth == depthWidth) &&
                                           (secondaryHeight == depthHeight) &&
                                           (secondaryRowStride > 0) &&
                                           (secondaryPixelStride >= static_cast<int32_t>(sizeof(uint16_t)));
                        }
                    }

                    const bool validDepth = imgData && (dataLength > 0) &&
                                            (rowStride > 0) &&
                                            (pixelStride >= static_cast<int32_t>(sizeof(uint16_t)));

                    depth_telemetry.width = depthWidth;
                    depth_telemetry.height = depthHeight;

                    //convert depthmap to pointcloud
                    int minConfidence = has_depth_sensor ? 32 : 128;
                    float maxErrorFilter = resolution * 3.0f;
                    float maxErrorHoles = resolution * 3.0f;
                    float maxErrorWalls = resolution * 3.0f;
                    double len = 100 - 0.001f; //far - near
                    std::vector<glm::vec3> refused;
                    std::map<std::pair<int, int>, double> edges2d;
                    glm::vec3 cam = glm::inverse(view_mat)[3];
                    glm::dmat4 screen2world = glm::inverse(projection_mat * view_mat);
                    if (validDepth && (lastDepthTimestamp != timestamp)) {
                        // A fresh, readable depth image is the preferred metric
                        // source. Repeated/unavailable frames retain the feature
                        // cloud collected above as the proven legacy fallback.
                        points.clear();
                        depth_telemetry.fresh_frames++;
                        depth_telemetry.sampled = 0;
                        depth_telemetry.valid = 0;
                        depth_telemetry.accepted = 0;
                        depth_telemetry.hole_filled = 0;
                        depth_telemetry.rejected_outlier = 0;
                        depth_telemetry.rejected_range = 0;
                        depth_telemetry.raw_min_mm = 0;
                        depth_telemetry.raw_max_mm = 0;

                        float maxY = INT_MIN;
                        std::map<int, float> distances;
                        std::map<std::pair<int, int>, float> distancesLocal;
                        float s = 1;
                        int m = has_depth_sensor
                                ? glm::max(0, (depthWidth - depthHeight) / 2)
                                : 0;
                        const float targetWidth = useDepthRaw ? 240.0f : 320.0f;
                        if (depthWidth > targetWidth) s = depthWidth / targetWidth;
                        for (float fy = 0; fy < depthHeight; fy += s) {
                            for (float fx = m; fx < depthWidth - m; fx += s) {
                                int x = (int)fx;
                                int y = (int)fy;
                                if ((x < 4) && (y == 0))
                                    continue;

                                depth_telemetry.sampled++;

                                //check point validity
                                uint16_t depthMm = ReadDepthPixel(imgData, rowStride, pixelStride, x, y);
                                if (depthMm > 0) {
                                    depth_telemetry.valid++;
                                    if (depth_telemetry.raw_min_mm == 0 ||
                                        depthMm < depth_telemetry.raw_min_mm)
                                        depth_telemetry.raw_min_mm = depthMm;
                                    if (depthMm > depth_telemetry.raw_max_mm)
                                        depth_telemetry.raw_max_mm = depthMm;
                                }
                                double depth = depthMm * 0.001f;
                                if (hasConfidence) {
                                    if (ReadConfidencePixel(confidenceData, confidenceRowStride,
                                                            confidencePixelStride, x, y) <= minConfidence) {
                                        if (hasSecondary) {

                                            //get nearest point with high confidence in 4 directions
                                            bool left = false, right = false, up = false, down = false;
                                            glm::vec3 c, l, r, u, d;
                                            for (int tx = x; tx >= 0; tx--) {
                                                if (ReadConfidencePixel(confidenceData, confidenceRowStride,
                                                                        confidencePixelStride, tx, y) > minConfidence) {
                                                    l = glm::vec3(tx, y, ReadDepthPixel(imgData, rowStride,
                                                                                       pixelStride, tx, y) * 0.001f);
                                                    left = true;
                                                    break;
                                                }
                                            }
                                            for (int tx = x; tx < depthWidth; tx++) {
                                                if (ReadConfidencePixel(confidenceData, confidenceRowStride,
                                                                        confidencePixelStride, tx, y) > minConfidence) {
                                                    r = glm::vec3(tx, y, ReadDepthPixel(imgData, rowStride,
                                                                                       pixelStride, tx, y) * 0.001f);
                                                    right = true;
                                                    break;
                                                }
                                            }
                                            for (int ty = y; ty >= 0; ty--) {
                                                if (ReadConfidencePixel(confidenceData, confidenceRowStride,
                                                                        confidencePixelStride, x, ty) > minConfidence) {
                                                    u = glm::vec3(x, ty, ReadDepthPixel(imgData, rowStride,
                                                                                       pixelStride, x, ty) * 0.001f);
                                                    up = true;
                                                    break;
                                                }
                                            }
                                            for (int ty = y; ty < depthHeight; ty++) {
                                                if (ReadConfidencePixel(confidenceData, confidenceRowStride,
                                                                        confidencePixelStride, x, ty) > minConfidence) {
                                                    d = glm::vec3(x, ty, ReadDepthPixel(imgData, rowStride,
                                                                                       pixelStride, x, ty) * 0.001f);
                                                    down = true;
                                                    break;
                                                }
                                            }
                                            c = glm::vec3(x, y, ReadDepthPixel(imgData2, secondaryRowStride,
                                                                               secondaryPixelStride, x, y) * 0.001f);

                                            //"closed" holes filling
                                            bool horizontal = false, vertical = false;
                                            if (left && right) {
                                                glm::vec3 v = glm::lerp(l, r, (c.x - l.x) / (r.x - l.x));
                                                if (fabs(v.z - c.z) < maxErrorHoles) {
                                                    horizontal = true;
                                                }
                                            }
                                            if (up && down) {
                                                glm::vec3 v = glm::lerp(u, d, (c.y - u.y) / (d.y - u.y));
                                                if (fabs(v.z - c.z) < maxErrorHoles) {
                                                    vertical = true;
                                                }
                                            }
                                            if (horizontal || vertical) {
                                                depth = c.z;
                                            } else {

                                                //store the refused point to process later
                                                depth = c.z;
                                                refused.emplace_back(ToPoint(screen2world, len, depthWidth, depthHeight, x, y, depth));

                                                //mark edge points for wall validation
                                                if (up && !down) edges2d[std::pair<int, int>(u.x, u.y)] = u.z;
                                                if (!up && down) edges2d[std::pair<int, int>(d.x, d.y)] = d.z;
                                                if (left && !right) edges2d[std::pair<int, int>(l.x, l.y)] = l.z;
                                                if (!left && right) edges2d[std::pair<int, int>(r.x, r.y)] = r.z;
                                                continue;
                                            }
                                        } else {
                                            continue;
                                        }
                                    }
                                }

                                //filter depth noise
                                if (hasSecondary) {
                                    double filtered = ReadDepthPixel(imgData2, secondaryRowStride,
                                                                     secondaryPixelStride, x, y) * 0.001f;
                                    if (fabs(depth - filtered) < maxErrorFilter) {
                                        depth = filtered;
                                    }
                                }

                                //add point into output
                                // Record the configured-range mismatch but let
                                // Tango3DR apply its own calibrated min/max
                                // handling. The extra hard gate introduced in
                                // 3.0.6 rejected virtually the whole Xiaomi map.
                                if (depth < depth_min || depth > depth_max)
                                    depth_telemetry.rejected_range++;
                                if (depth > 0.05) {
                                    depth -= offset;
                                    glm::vec4 p = ToPoint(screen2world, len, depthWidth, depthHeight, x, y, depth);
                                    if (maxY < p.y) maxY = p.y;
                                    points.emplace_back(p);

                                    if (!has_depth_sensor) {
                                        int dir = (int)glm::degrees(atan2(p.y - cam.y, p.x - cam.x));
                                        float dst = glm::distance(cam, glm::vec3(p));
                                        if (distances.find(dir) == distances.end()) {
                                            distances[dir] = dst;
                                        } else if (distances[dir] > dst) {
                                            distances[dir] = dst;
                                        }
                                        std::pair<int, int> key;
                                        key.first = dir;
                                        key.second = (int)(p.y * 10);
                                        if (distancesLocal.find(key) == distancesLocal.end()) {
                                            distancesLocal[key] = dst;
                                        } else if (distancesLocal[key] > dst) {
                                            distancesLocal[key] = dst;
                                        }
                                    }
                                }
                            }
                        }


                        //convert edge points into 3D space
                        std::vector<glm::vec3> edges3d;
                        for (std::pair<const std::pair<int, int>, double>& e : edges2d) {
                            int x = e.first.first;
                            int y = e.first.second;
                            double depth = e.second;
                            edges3d.emplace_back(ToPoint(screen2world, len, depthWidth, depthHeight, x, y, depth));
                        }

                        //add wall points
                        for (glm::vec3& r : refused) {
                            bool ok = false;
                            glm::vec3 v(INT_MIN);
                            for (glm::vec3& p : edges3d) {
                                if ((r.y > p.y) && (r.y < maxY)) {
                                    float x = fabs(p.x - r.x);
                                    float z = fabs(p.z - r.z);
                                    if (x * x + z * z < maxErrorWalls * maxErrorWalls) {
                                        if (v.y < p.y) {
                                            ok = true;
                                            v = p;
                                        }
                                    }
                                }
                            }
                            if (ok) {
                                int dir = (int)glm::degrees(atan2(v.y - cam.y, v.x - cam.x));
                                if (distances.find(dir) != distances.end()) {
                                    float dst = glm::distance(cam, v);
                                    if ((distances[dir] - maxErrorWalls < dst)) {
                                        std::pair<int, int> key;
                                        key.first = dir;
                                        key.second = (int)(r.y * 10);
                                        if (distancesLocal.find(key) == distancesLocal.end()) {
                                            points.emplace_back(r, 1.0f);
                                        } else if (distancesLocal[key] + maxErrorWalls > dst) {
                                            points.emplace_back(r, 1.0f);
                                        }
                                    }
                                }
                            }
                        }
                        depth_telemetry.accepted = static_cast<int>(points.size());
                    } else if (validDepth) {
                        depth_telemetry.repeated_frames++;
                    } else {
                        depth_telemetry.unavailable_frames++;
                    }

                    //cleanup
                    if (confidenceImage) {
                        ArImage_release(confidenceImage);
                    }
                    if (image2) {
                        ArImage_release(image2);
                    }
                    ArImage_release(image);
                    if (validDepth && (lastDepthTimestamp != timestamp))
                        lastDepthTimestamp = timestamp;
                } else {
                    depth_telemetry.unavailable_frames++;
                }
        }
        has_coordinate_system_ = true;
    }
}
