"""Generate synthetic reference access units. Development dependency: PyAV 16.1.0/libx264.
No captured content is read. Run from the repository root; decoding is checked before writing.
"""
from fractions import Fraction
from pathlib import Path
import hashlib
import re
import av

GLYPHS = {
'T':['11111','00100','00100','00100','00100','00100','00100'],
'E':['11111','10000','10000','11110','10000','10000','11111'],
'S':['11111','10000','10000','11111','00001','00001','11111'],
'P':['11110','10001','10001','11110','10000','10000','10000'],
'A':['01110','10001','10001','11111','10001','10001','10001'],
'R':['11110','10001','10001','11110','10100','10010','10001'],
'N':['10001','11001','10101','10011','10001','10001','10001'],
}
width,height=960,452
root=Path('app/src/main/assets/video_reference')
outputs=[]
for ordinal,background in enumerate(((20,90,160),(130,35,80))):
    data=bytearray(bytes(background)*(width*height))
    text='TEST PATTERN'; scale=12; x0=(width-len(text)*6*scale)//2; y0=170
    for index,char in enumerate(text):
        for y,row in enumerate(GLYPHS.get(char, ['00000']*7)):
            for x,pixel in enumerate(row):
                if pixel=='1':
                    for dy in range(scale):
                        start=((y0+y*scale+dy)*width+x0+(index*6+x)*scale)*3
                        data[start:start+scale*3]=b'\xff'*(scale*3)
    frame=av.VideoFrame(width,height,'rgb24'); frame.planes[0].update(data)
    encoder=av.CodecContext.create('libx264','w')
    encoder.thread_count=1
    encoder.width=width; encoder.height=height; encoder.pix_fmt='yuv420p'
    encoder.time_base=Fraction(1,5); encoder.framerate=Fraction(5,1)
    encoder.options={'profile':'baseline','level':'3.1','preset':'medium','tune':'zerolatency',
                     'x264-params':'keyint=1:min-keyint=1:repeat-headers=1:annexb=1:slices=1:sliced-threads=0:crf=20'}
    frame.pts=0
    payload=b''.join(bytes(p) for p in encoder.encode(frame)+encoder.encode(None))
    decoder=av.CodecContext.create('h264','r')
    decoded=decoder.decode(av.Packet(payload))+decoder.decode(None)
    assert len(decoded)==1 and (decoded[0].width,decoded[0].height)==(width,height)
    nalus=[n for n in re.split(b'\x00\x00\x00?\x01',payload) if n]
    assert [n[0]&31 for n in nalus]==[7,8,6,5]
    assert len(nalus[-1])>1200  # The single IDR requires FU-A at the normal WebRTC MTU.
    assert nalus[0][1:4]==bytes((66,192,31))
    assert 1200 < len(payload) < 32768
    outputs.append((root/f'pattern-{ordinal}.h264',payload))
assert outputs[0][1]!=outputs[1][1]
for path,payload in outputs:
    path.write_bytes(payload)
    print(path.name,len(payload),hashlib.sha256(payload).hexdigest())
