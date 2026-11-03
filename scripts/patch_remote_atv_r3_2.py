import zipfile, os, hashlib, zlib, struct, sys
src=sys.argv[1]
out=sys.argv[2]
old=b'flutter.remote_atv_patch_r1_defaults_applied'
new=b'flutter.remote_atv_patch_r3_defaults_applied'
assert len(old)==len(new)
patches={
  0x25d4a0:(bytes.fromhex('60e74091'),bytes.fromhex('60eb44f9'),'Share label getter -> empty string'),
  0x25d4a4:(bytes.fromhex('00e844f9'),bytes.fromhex('1f2003d5'),'Share label getter NOP'),
  0x265260:(bytes.fromhex('60e74091'),bytes.fromhex('60eb44f9'),'Review label getter -> empty string'),
  0x265264:(bytes.fromhex('00e444f9'),bytes.fromhex('1f2003d5'),'Review label getter NOP'),
  0x282220:(bytes.fromhex('60e34091'),bytes.fromhex('60eb44f9'),'Feedback label getter -> empty string'),
  0x282224:(bytes.fromhex('002c46f9'),bytes.fromhex('1f2003d5'),'Feedback label getter NOP'),
  0x3a03b4:(bytes.fromhex('60e74091'),bytes.fromhex('60eb44f9'),'Premium label getter -> empty string'),
  0x3a03b8:(bytes.fromhex('007044f9'),bytes.fromhex('1f2003d5'),'Premium label getter NOP'),
  0x44cdc8:(bytes.fromhex('21bc44f9'),bytes.fromhex('e10316aa'),'Share callback disabled'),
  0x44cf00:(bytes.fromhex('21c444f9'),bytes.fromhex('e10316aa'),'Review callback disabled'),
  0x44d008:(bytes.fromhex('21cc44f9'),bytes.fromhex('e10316aa'),'Feedback callback disabled'),
  0x44d2ac:(bytes.fromhex('21dc44f9'),bytes.fromhex('e10316aa'),'Premium callback disabled'),
}
blank_svg=b'<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1" viewBox="0 0 1 1"></svg>'
svg_names=[f'assets/flutter_assets/assets/images/{n}.svg' for n in ('share','review','feedback','workspace_premium')]
with zipfile.ZipFile(src,'r') as zin:
    infos=zin.infolist()
    entries={i.filename:zin.read(i.filename) for i in infos if not (i.filename=='META-INF/MANIFEST.MF' or (i.filename.startswith('META-INF/') and i.filename.upper().endswith(('.SF','.RSA','.DSA','.EC'))))}
dex=bytearray(entries['classes3.dex'])
assert dex.count(old)==1 and old in dex, 'R2 migration marker mismatch'
pos=dex.index(old); dex[pos:pos+len(old)]=new
dex[12:32]=hashlib.sha1(dex[32:]).digest()
dex[8:12]=struct.pack('<I',zlib.adler32(dex[12:])&0xffffffff)
entries['classes3.dex']=bytes(dex)
lib=bytearray(entries['lib/arm64-v8a/libapp.so'])
for off,(before,after,desc) in patches.items():
    actual=bytes(lib[off:off+4])
    assert actual==before, f'Unexpected A64 bytes at {off:#x}: {actual.hex()} != {before.hex()} ({desc})'
    lib[off:off+4]=after
    print(f'PATCH {off:#x}: {desc}')
entries['lib/arm64-v8a/libapp.so']=bytes(lib)
for n in svg_names:
    assert n in entries, f'missing asset {n}'
    entries[n]=blank_svg
with zipfile.ZipFile(out,'w',allowZip64=True) as zout:
    for i in infos:
        if i.filename not in entries: continue
        ni=zipfile.ZipInfo(i.filename,i.date_time)
        ni.compress_type=i.compress_type
        ni.comment=i.comment; ni.extra=i.extra
        ni.create_system=i.create_system
        ni.external_attr=i.external_attr; ni.internal_attr=i.internal_attr
        ni.flag_bits=i.flag_bits
        zout.writestr(ni,entries[i.filename],compress_type=i.compress_type,compresslevel=6)
print('OUTPUT',out,os.path.getsize(out))
print('DEX_SHA1',dex[12:32].hex())
print('LIBAPP_SHA256',hashlib.sha256(lib).hexdigest())
print('APK_SHA256',hashlib.sha256(open(out,'rb').read()).hexdigest())
