"""列出结构包里每个模板的尺寸与方块数。"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import blueprint_to_nbt as bp

d = pathlib.Path(__file__).resolve().parent.parent / "datapacks" / "plugmc-structures" / "data" / "plugmc" / "structure"
for p in sorted(d.glob("*.nbt")):
    s = bp.read_structure(p)
    print("{:14s} size={:14s} blocks={}".format(p.stem, str(s["size"]), len(s["blocks"])))
