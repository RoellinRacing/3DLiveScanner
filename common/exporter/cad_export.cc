#include "exporter/cad_export.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>

namespace oc {
namespace {

bool Finite(const glm::vec3& value) {
    return std::isfinite(value.x) && std::isfinite(value.y) && std::isfinite(value.z);
}

size_t CandidateTriangleCount(const Mesh& mesh) {
    return mesh.indices.empty() ? mesh.vertices.size() / 3 : mesh.indices.size() / 3;
}

bool TriangleAt(const Mesh& mesh, size_t triangle,
                glm::vec3& a, glm::vec3& b, glm::vec3& c,
                uint32_t* ia = nullptr, uint32_t* ib = nullptr, uint32_t* ic = nullptr) {
    uint32_t x;
    uint32_t y;
    uint32_t z;
    const size_t first = triangle * 3;

    if (mesh.indices.empty()) {
        if (first + 2 >= mesh.vertices.size()) return false;
        x = static_cast<uint32_t>(first);
        y = static_cast<uint32_t>(first + 1);
        z = static_cast<uint32_t>(first + 2);
    } else {
        if (first + 2 >= mesh.indices.size()) return false;
        x = mesh.indices[first];
        y = mesh.indices[first + 1];
        z = mesh.indices[first + 2];
        if (x >= mesh.vertices.size() || y >= mesh.vertices.size() || z >= mesh.vertices.size()) {
            return false;
        }
    }

    a = mesh.vertices[x];
    b = mesh.vertices[y];
    c = mesh.vertices[z];
    if (!Finite(a) || !Finite(b) || !Finite(c)) return false;

    const glm::vec3 cross = glm::cross(b - a, c - a);
    const float length2 = glm::dot(cross, cross);
    if (!std::isfinite(length2) || length2 <= 1e-20f) return false;

    if (ia) *ia = x;
    if (ib) *ib = y;
    if (ic) *ic = z;
    return true;
}

uint64_t ValidTriangleCount(const std::vector<Mesh>& meshes) {
    uint64_t count = 0;
    glm::vec3 a;
    glm::vec3 b;
    glm::vec3 c;
    for (const Mesh& mesh : meshes) {
        const size_t candidates = CandidateTriangleCount(mesh);
        for (size_t i = 0; i < candidates; ++i) {
            if (TriangleAt(mesh, i, a, b, c)) ++count;
        }
    }
    return count;
}

bool WriteBytes(FILE* file, const void* data, size_t size) {
    return std::fwrite(data, 1, size, file) == size;
}

bool WriteU16LE(FILE* file, uint16_t value) {
    const unsigned char bytes[2] = {
        static_cast<unsigned char>(value & 0xffu),
        static_cast<unsigned char>((value >> 8u) & 0xffu)
    };
    return WriteBytes(file, bytes, sizeof(bytes));
}

bool WriteU32LE(FILE* file, uint32_t value) {
    const unsigned char bytes[4] = {
        static_cast<unsigned char>(value & 0xffu),
        static_cast<unsigned char>((value >> 8u) & 0xffu),
        static_cast<unsigned char>((value >> 16u) & 0xffu),
        static_cast<unsigned char>((value >> 24u) & 0xffu)
    };
    return WriteBytes(file, bytes, sizeof(bytes));
}

bool WriteF32LE(FILE* file, float value) {
    uint32_t bits = 0;
    static_assert(sizeof(bits) == sizeof(value), "32-bit float required");
    std::memcpy(&bits, &value, sizeof(bits));
    return WriteU32LE(file, bits);
}

bool WriteVec3LE(FILE* file, const glm::vec3& value) {
    return WriteF32LE(file, value.x) &&
           WriteF32LE(file, value.y) &&
           WriteF32LE(file, value.z);
}

uint32_t PackedRed(uint32_t color) {
    return color & 0xffu;
}

uint32_t PackedGreen(uint32_t color) {
    return (color >> 8u) & 0xffu;
}

uint32_t PackedBlue(uint32_t color) {
    return (color >> 16u) & 0xffu;
}

}  // namespace

bool CadExport::WriteBinaryStl(const std::string& filename,
                               const std::vector<Mesh>& meshes,
                               float scale) {
    if (!std::isfinite(scale) || scale <= 0.0f) return false;

    const uint64_t triangleCount64 = ValidTriangleCount(meshes);
    if (triangleCount64 == 0 ||
        triangleCount64 > std::numeric_limits<uint32_t>::max()) {
        return false;
    }

    FILE* file = std::fopen(filename.c_str(), "wb");
    if (!file) return false;

    unsigned char header[80] = {};
    const char label[] = "3DLiveScanner binary STL; coordinates in millimetres";
    std::memcpy(header, label, sizeof(label) - 1);
    bool ok = WriteBytes(file, header, sizeof(header)) &&
              WriteU32LE(file, static_cast<uint32_t>(triangleCount64));

    glm::vec3 a;
    glm::vec3 b;
    glm::vec3 c;
    for (const Mesh& mesh : meshes) {
        const size_t candidates = CandidateTriangleCount(mesh);
        for (size_t i = 0; ok && i < candidates; ++i) {
            if (!TriangleAt(mesh, i, a, b, c)) continue;

            const glm::vec3 normal = glm::normalize(glm::cross(b - a, c - a));
            a *= scale;
            b *= scale;
            c *= scale;
            ok = WriteVec3LE(file, normal) &&
                 WriteVec3LE(file, a) &&
                 WriteVec3LE(file, b) &&
                 WriteVec3LE(file, c) &&
                 WriteU16LE(file, 0u);
        }
    }

    if (std::ferror(file)) ok = false;
    if (std::fclose(file) != 0) ok = false;
    if (!ok) std::remove(filename.c_str());
    return ok;
}

bool CadExport::Write3mfModelXml(const std::string& filename,
                                 const std::vector<Mesh>& meshes,
                                 float scale) {
    if (!std::isfinite(scale) || scale <= 0.0f || ValidTriangleCount(meshes) == 0) {
        return false;
    }

    FILE* file = std::fopen(filename.c_str(), "w");
    if (!file) return false;

    std::vector<const Mesh*> exported;
    glm::vec3 a;
    glm::vec3 b;
    glm::vec3 c;
    for (const Mesh& mesh : meshes) {
        for (const glm::vec3& vertex : mesh.vertices) {
            if (!Finite(vertex)) {
                std::fclose(file);
                std::remove(filename.c_str());
                return false;
            }
        }

        bool hasTriangle = false;
        const size_t candidates = CandidateTriangleCount(mesh);
        for (size_t i = 0; i < candidates; ++i) {
            if (TriangleAt(mesh, i, a, b, c)) {
                hasTriangle = true;
                break;
            }
        }
        if (hasTriangle) exported.push_back(&mesh);
    }

    std::fprintf(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    std::fprintf(file,
        "<model unit=\"millimeter\" xml:lang=\"en-US\" "
        "xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n");
    std::fprintf(file,
        " <metadata name=\"Title\">3DLiveScanner export</metadata>\n <resources>\n");

    for (size_t object = 0; object < exported.size(); ++object) {
        const Mesh& mesh = *exported[object];
        std::fprintf(file,
            "  <object id=\"%u\" type=\"model\"><mesh><vertices>\n",
            static_cast<unsigned int>(object + 1));
        for (const glm::vec3& vertex : mesh.vertices) {
            std::fprintf(file,
                "   <vertex x=\"%.9g\" y=\"%.9g\" z=\"%.9g\"/>\n",
                vertex.x * scale, vertex.y * scale, vertex.z * scale);
        }

        std::fprintf(file, "  </vertices><triangles>\n");
        const size_t candidates = CandidateTriangleCount(mesh);
        for (size_t i = 0; i < candidates; ++i) {
            uint32_t ia;
            uint32_t ib;
            uint32_t ic;
            if (!TriangleAt(mesh, i, a, b, c, &ia, &ib, &ic)) continue;
            std::fprintf(file,
                "   <triangle v1=\"%u\" v2=\"%u\" v3=\"%u\"/>\n",
                ia, ib, ic);
        }
        std::fprintf(file, "  </triangles></mesh></object>\n");
    }

    std::fprintf(file, " </resources>\n <build>\n");
    for (size_t object = 0; object < exported.size(); ++object) {
        std::fprintf(file, "  <item objectid=\"%u\"/>\n",
                     static_cast<unsigned int>(object + 1));
    }
    std::fprintf(file, " </build>\n</model>\n");

    bool ok = std::ferror(file) == 0;
    if (std::fclose(file) != 0) ok = false;
    if (!ok) std::remove(filename.c_str());
    return ok;
}

bool CadExport::WriteAsciiPly(const std::string& filename,
                              const std::vector<Mesh>& meshes) {
    uint64_t vertexCount64 = 0;
    for (const Mesh& mesh : meshes) vertexCount64 += mesh.vertices.size();
    const uint64_t faceCount64 = ValidTriangleCount(meshes);
    if (vertexCount64 == 0 ||
        vertexCount64 > std::numeric_limits<uint32_t>::max() ||
        faceCount64 > std::numeric_limits<uint32_t>::max()) {
        return false;
    }

    FILE* file = std::fopen(filename.c_str(), "w");
    if (!file) return false;

    std::fprintf(file,
        "ply\nformat ascii 1.0\ncomment units metre\n"
        "element vertex %u\n"
        "property float x\nproperty float y\nproperty float z\n"
        "property float nx\nproperty float ny\nproperty float nz\n"
        "property uchar red\nproperty uchar green\nproperty uchar blue\n"
        "element face %u\n"
        "property list uchar uint vertex_indices\nend_header\n",
        static_cast<unsigned int>(vertexCount64),
        static_cast<unsigned int>(faceCount64));

    for (const Mesh& mesh : meshes) {
        for (size_t i = 0; i < mesh.vertices.size(); ++i) {
            const glm::vec3& vertex = mesh.vertices[i];
            if (!Finite(vertex)) {
                std::fclose(file);
                std::remove(filename.c_str());
                return false;
            }

            glm::vec3 normal(0.0f);
            if (i < mesh.normals.size() && Finite(mesh.normals[i])) {
                normal = mesh.normals[i];
            }
            const uint32_t color = i < mesh.colors.size()
                ? mesh.colors[i]
                : 0x00ccccccu;
            std::fprintf(file,
                "%.9g %.9g %.9g %.9g %.9g %.9g %u %u %u\n",
                vertex.x, vertex.y, vertex.z,
                normal.x, normal.y, normal.z,
                PackedRed(color), PackedGreen(color), PackedBlue(color));
        }
    }

    uint64_t offset = 0;
    glm::vec3 a;
    glm::vec3 b;
    glm::vec3 c;
    for (const Mesh& mesh : meshes) {
        const size_t candidates = CandidateTriangleCount(mesh);
        for (size_t i = 0; i < candidates; ++i) {
            uint32_t ia;
            uint32_t ib;
            uint32_t ic;
            if (!TriangleAt(mesh, i, a, b, c, &ia, &ib, &ic)) continue;
            std::fprintf(file, "3 %u %u %u\n",
                static_cast<unsigned int>(offset + ia),
                static_cast<unsigned int>(offset + ib),
                static_cast<unsigned int>(offset + ic));
        }
        offset += mesh.vertices.size();
    }

    bool ok = std::ferror(file) == 0;
    if (std::fclose(file) != 0) ok = false;
    if (!ok) std::remove(filename.c_str());
    return ok;
}

}  // namespace oc
