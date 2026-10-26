"""Run on NUC. Validate real Android synthetic Opus and native wire packetization."""
import pathlib,struct,subprocess,json,tempfile,os
base=pathlib.Path(__file__).resolve().parents[1]
payload=pathlib.Path(os.environ['OPUS_FIXTURE']).read_bytes();at=0;packets=[]
while at<len(payload):
 pts,size=struct.unpack_from('>qI',payload,at);at+=12;data=payload[at:at+size];at+=size;packets.append((pts,data))
assert len(packets)==100
assert all(b[0]-a[0]==20000 for a,b in zip(packets,packets[1:]))
# Verify self-describing Opus TOC: CELT 20ms, one mono frame per packet.
for _,p in packets:
 assert p[0]&3==0 and not p[0]&4
 config=p[0]>>3
 duration=(2.5*(2**(config&3)) if config>=16 else (10*(2**(config&1)) if config>=12 else [10,20,40,60][config&3]))
 assert duration==20,(config,duration)
def crc(b):
 r=0
 for v in b:
  r^=v<<24
  for _ in range(8):r=((r<<1)^0x04c11db7 if r&0x80000000 else r<<1)&0xffffffff
 return r
def page(p,seq,granule,flags=0):
 sizes=[255]*(len(p)//255)+[len(p)%255]
 b=bytearray(b'OggS'+bytes([0,flags])+struct.pack('<QII',granule,42,seq)+b'\0'*4+bytes([len(sizes)])+bytes(sizes)+p);struct.pack_into('<I',b,22,crc(b));return b
with tempfile.TemporaryDirectory(prefix='portal-opus-verify-') as d:
 d=pathlib.Path(d);ogg=page(b'OpusHead'+bytes([1,1])+struct.pack('<HIhB',312,48000,0,0),0,0,2)
 ogg+=page(b'OpusTags'+struct.pack('<II',0,0),1,0)
 for i,(_,p) in enumerate(packets):ogg+=page(p,i+2,(i+1)*960,4 if i==len(packets)-1 else 0)
 (d/'test.ogg').write_bytes(ogg)
 r=subprocess.run(['ffmpeg','-nostdin','-v','error','-i',str(d/'test.ogg'),'-f','null','-'],capture_output=True,text=True,timeout=15);assert r.returncode==0 and not r.stderr,r.stderr
 subprocess.run(['javac','-d',str(d),str(base/'app/ProtectMuxer.java'),str(base/'tests/OpusMuxerFixture.java')],check=True,capture_output=True)
 subprocess.run(['java','-cp',str(d),'local.codex.portalprotectcamera.OpusMuxerFixture',os.environ['OPUS_FIXTURE'],str(d/'native.flv')],check=True)
 raw=(d/'native.flv').read_bytes();at=13;tags=[]
 while at<len(raw):
  h=raw[at:at+11];k=h[0];n=int.from_bytes(h[1:4],'big');stamp=int.from_bytes(h[4:7],'big')+(h[7]<<24);p=raw[at+11:at+11+n];end=at+11+n
  assert int.from_bytes(raw[end:end+4],'big')==11+n
  if k==10:
   trailer=raw[end+4:end+20];assert int.from_bytes(trailer[1:4],'big')==48000;assert int.from_bytes(trailer[12:16],'big')==stamp*48;tags.append((stamp,p))
  at=end+20
 assert tags[0]==(29925,bytes.fromhex('cf000300'))
 assert tags[1:]==[(30000+int(pts/1000)-75,p) for pts,p in packets]
 subprocess.run(['java','-cp',str(d),'local.codex.portalprotectcamera.OpusMuxerFixture',os.environ['OPUS_FIXTURE'],str(d/'opus-only.flv'),'false'],check=True)
 only=(d/'opus-only.flv').read_bytes();at=13;counts={};opus_only_packets=[]
 while at<len(only):
  h=only[at:at+11];k=h[0];n=int.from_bytes(h[1:4],'big');counts[k]=counts.get(k,0)+1
  if k==10:opus_only_packets.append(only[at+11:at+11+n])
  at+=11+n+20
 assert counts.get(8,0)==0 and b'audiocodecid' not in only
 assert opus_only_packets==[bytes.fromhex('cf000300')]+[p for _,p in packets]
 print(json.dumps({'opus_only_zero_aac_headers_and_packets':True,'android_opus_packets' :len(packets),'mono':True,'packet_duration_ms':20,'decoded_without_errors':True,'native_payloads_preserved':True,'native_tag_type':10,'native_clock_rate':48000,'timestamp_advance_ms':75,'first_encoder_pts_us':packets[0][0]},indent=2))
