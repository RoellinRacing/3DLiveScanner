#!/usr/bin/env python3
"""Reject Android layout views without effective width/height attributes."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
LAYOUT = RES / "layout"


def resource_name(value, prefix):
    return value[len(prefix):] if value and value.startswith(prefix) else None


styles = {}
parents = {}
for style_file in sorted(RES.glob("values*/styles.xml")):
    for style in ET.parse(style_file).getroot().findall("style"):
        name = style.get("name")
        if not name:
            continue
        styles[name] = {
            item.get("name"): (item.text or "").strip()
            for item in style.findall("item")
            if item.get("name")
        }
        parent = style.get("parent")
        if parent:
            parents[name] = resource_name(parent, "@style/") or parent
        elif "." in name:
            parents[name] = name.rsplit(".", 1)[0]


def style_has(style_name, attribute, seen=None):
    if not style_name:
        return False
    seen = set() if seen is None else seen
    if style_name in seen:
        return False
    seen.add(style_name)
    if attribute in styles.get(style_name, {}):
        return True
    return style_has(parents.get(style_name), attribute, seen)


def element_has(element, attribute):
    if ANDROID + attribute in element.attrib:
        return True
    style_name = resource_name(element.attrib.get("style"), "@style/")
    return style_has(style_name, "android:" + attribute)


layout_roots = {
    path.stem: ET.parse(path).getroot()
    for path in sorted(LAYOUT.glob("*.xml"))
}


def include_has(element, attribute):
    if element_has(element, attribute):
        return True
    target = resource_name(element.attrib.get("layout"), "@layout/")
    root = layout_roots.get(target)
    return root is not None and element_has(root, attribute)


failures = []
ignored_tags = {"merge", "requestFocus", "tag"}
for path in sorted(LAYOUT.glob("*.xml")):
    root = layout_roots[path.stem]
    for element in root.iter():
        tag = element.tag.rsplit("}", 1)[-1]
        if tag in ignored_tags:
            continue
        checker = include_has if tag == "include" else element_has
        missing = [
            name for name in ("layout_width", "layout_height")
            if not checker(element, name)
        ]
        if missing:
            view_id = element.attrib.get(ANDROID + "id", "<no id>")
            failures.append(
                f"{path.relative_to(ROOT)}: <{tag}> {view_id} missing "
                + ", ".join("android:" + name for name in missing)
            )

if failures:
    print("Invalid Android layout dimensions:", file=sys.stderr)
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)

print(f"Validated {len(layout_roots)} Android layout files")
