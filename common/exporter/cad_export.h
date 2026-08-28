#ifndef EXPORTER_CAD_EXPORT_H
#define EXPORTER_CAD_EXPORT_H

#include <string>
#include <vector>

#include "data/mesh.h"

namespace oc {

class CadExport {
public:
    // The scanner scene uses metres. STL has no unit metadata, so coordinates
    // are converted to millimetres by default.
    static bool WriteBinaryStl(const std::string& filename,
                               const std::vector<Mesh>& meshes,
                               float metresToMillimetres = 1000.0f);

    // Writes the 3D/3dmodel.model XML payload. Android packages this payload
    // into the final 3MF ZIP container.
    static bool Write3mfModelXml(const std::string& filename,
                                 const std::vector<Mesh>& meshes,
                                 float metresToMillimetres = 1000.0f);

    // PLY remains in scanner-native metres and records the unit in its header.
    static bool WriteAsciiPly(const std::string& filename,
                              const std::vector<Mesh>& meshes);
};

}  // namespace oc

#endif
