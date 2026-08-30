#ifndef ARCORE_ARCORE_H
#define ARCORE_ARCORE_H

#include <map>
#include <jni.h>
#include <arcore/camera.h>
#include <data/image.h>
#include <data/mesh.h>
#include <gl/renderer.h>

namespace oc {

    struct DepthTelemetry {
        int width = 0;
        int height = 0;
        int sampled = 0;
        int valid = 0;
        int accepted = 0;
        int hole_filled = 0;
        int rejected_outlier = 0;
        int rejected_range = 0;
        int raw_min_mm = 0;
        int raw_max_mm = 0;
        int feature_points = 0;
        int acquire_status = 0;
        long long fresh_frames = 0;
        long long repeated_frames = 0;
        long long unavailable_frames = 0;
    };

    class ARCore {
    public:
        ARCore(void *env, void *context, bool faceMode, bool depthCamera = false);

        ~ARCore();

        void Clear(bool detach);

        void OnPause();

        void OnResume();

        void OnDisplayGeometryChanged(int display_rotation, int width, int height);

        void Configure(void* session, void* frame);

        float CountFrameError();

        bool Process(bool update = true);

        std::vector<glm::vec3> GetActiveAnchors();

        std::vector<float> GetDistortion();

        glm::vec3 HitTest(int x, int y);

        Mesh GetFace(glm::mat4 matrix) { UpdateFace(matrix); return face_mesh; };

        std::vector<glm::vec4> GetPointCloud() { UpdateFeaturePoints(); return points; }

        glm::mat4 GetProjection() { return projection_mat; }

        glm::mat4 GetView() { return view_mat; }

        bool HasCoordinateSystem() { return has_coordinate_system_; }

        void RemoveFaceDetails() { face_not_all = true; }

        void RenderCamera(ARCoreCamera::Effect effect = ARCoreCamera::GRAYSCALE, int scale = 1);

        void SetNVScheme(ARCoreCamera::NightVisionScheme s) { camera.SetNVScheme(s); }

        void SetOffset(float value) { offset = value; }

        void SetResolution(float res) { resolution = res; }

        Image* GetDepthMap(bool confidence, bool increasing, int s = 1);

        bool IsReady() const { return session_ready_; }

        bool IsDepthEnabled() const { return session_ready_ && useDepth; }

        bool IsRawDepthEnabled() const { return session_ready_ && useDepthRaw; }

        bool HasHardwareDepthSensor() const {
            return session_ready_ && has_depth_sensor;
        }

        DepthTelemetry GetDepthTelemetry() const { return depth_telemetry; }

        void SetDepthRange(float min_depth, float max_depth) {
            depth_min = min_depth;
            depth_max = max_depth;
        }

    private:
        glm::mat4 GetMatrix(ArPose* ar_pose);

        glm::mat4 GetZeroTransform();

        glm::vec4 ToPoint(glm::dmat4& screen2world, double& len,
                int32_t& depthWidth, int32_t& depthHeight, int& x, int& y, double& depth);

        bool UpdateAnchor();

        void UpdateFace(glm::mat4 matrix);

        void UpdateFeaturePoints();

        std::map<id3d, ArAnchor*> ar_anchor_list;
        ArSession *ar_session_ = nullptr;
        ArFrame *ar_frame_ = nullptr;
        std::pair<id3d, ArAnchor*> ar_zero_;
        Mesh face_mesh;

        ARCoreCamera camera;

        glm::mat4 view_mat;
        glm::mat4 projection_mat;

        bool face_mode_;
        bool has_coordinate_system_;
        bool has_depth_sensor;
        std::vector<glm::vec4> points;
        float offset;
        float resolution;
        bool face_not_all = false;
        bool texture_initialized_ = false;
        bool session_ready_ = false;
        bool useDepth;
        bool useDepthRaw;
        float depth_min;
        float depth_max;
        DepthTelemetry depth_telemetry;
        int viewportWidth;
        int viewportHeight;
        int64_t lastDepthTimestamp;
    };
}

#endif
