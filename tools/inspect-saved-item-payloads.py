import argparse, base64, collections, gzip, hashlib, io, json, struct
from pathlib import Path
import yaml
parser = argparse.ArgumentParser(description="Inspect original ItemEdit internal NBT without modifying the database")
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
source = args.source
root = yaml.safe_load(source.read_text(encoding='utf-8-sig'))
TYPES = ['end','byte','short','int','long','float','double','byte_array','string','list','compound','int_array','long_array']
def read_value(stream, kind):
    def unpack(fmt):
        size = struct.calcsize('>' + fmt)
        return struct.unpack('>' + fmt, stream.read(size))[0]
    def string():
        data = stream.read(unpack('H'))
        return data.replace(b'\xc0\x80', b'\0').decode('utf-8', errors='surrogatepass')
    if kind in (1,2,3,4,5,6): return unpack({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
    if kind == 7: return list(stream.read(unpack('i')))
    if kind == 8: return string()
    if kind == 9:
        subtype, size = unpack('B'), unpack('i')
        return {'element_type': TYPES[subtype], 'values': [read_value(stream, subtype) for _ in range(size)]}
    if kind == 10:
        result = {}
        while (subtype := unpack('B')) != 0:
            name = string()
            result[name] = {'type': TYPES[subtype], 'value': read_value(stream, subtype)}
        return result
    if kind in (11,12): return [unpack('i' if kind == 11 else 'q') for _ in range(unpack('i'))]
    raise ValueError(kind)
entries = []
meta_counts = collections.Counter()
versions = collections.Counter()
for key, saved in root.items():
    item = saved.get('item', {})
    meta = item.get('meta', {})
    meta_counts.update(meta.keys())
    versions.update([item.get('v')])
    if 'internal' not in meta: continue
    compressed = base64.b64decode(meta['internal'], validate=True)
    data = gzip.decompress(compressed)
    stream = io.BytesIO(data)
    assert stream.read(1) == b'\x0a'
    length = struct.unpack('>H', stream.read(2))[0]
    stream.read(length)
    parsed = read_value(stream, 10)
    assert not stream.read()
    entries.append({'id': str(key), 'type': item.get('type'), 'data_version': item.get('v'), 'compressed_bytes': len(compressed), 'nbt_bytes': len(data), 'internal_sha256': hashlib.sha256(compressed).hexdigest(), 'nbt': parsed})
result = {'source': str(source), 'sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'total_items': len(root), 'data_versions': dict(versions), 'metadata_counts': dict(meta_counts), 'internal_items': entries}
args.output.write_text(json.dumps(result, ensure_ascii=True, indent=2), encoding='utf-8')
print(json.dumps({"items": len(root), "internal_items": len(entries), "output": str(args.output)}, ensure_ascii=True))
