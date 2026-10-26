import subprocess,tempfile,pathlib,json,re,struct,collections,os
base=pathlib.Path(__file__).resolve().parents[1]
def run(args,**kw):return subprocess.run(args,check=True,**kw)
def raw(hex_dump):
 parts=[]
 for line in hex_dump.splitlines():
  if ':' in line:parts.append(line.split(':',1)[1].split('  ',1)[0].replace(' ',''))
 return bytes.fromhex(''.join(parts))
with tempfile.TemporaryDirectory(prefix='portal-native-mux-') as folder:
 p=pathlib.Path(folder)
 run(['ffmpeg','-nostdin','-v','error','-rtsp_transport','tcp','-i',os.environ['SOURCE_RTSP'],'-t','6','-c','copy','-y',str(p/'source.mp4')],timeout=20)
 info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_packets','-show_streams','-show_data','-of','json',str(p/'source.mp4')]))
 streams={s['index']:s for s in info['streams']};video=next(s for s in streams.values() if s['codec_type']=='video');avcc=raw(video['extradata']);at=6;n=int.from_bytes(avcc[at:at+2],'big');at+=2;sps=avcc[at:at+n];at+=n+1;n=int.from_bytes(avcc[at:at+2],'big');at+=2;pps=avcc[at:at+n]
 expected=collections.Counter()
 with (p/'fixture').open('wb') as f:
  f.write(struct.pack('>H',len(sps))+sps+struct.pack('>H',len(pps))+pps)
  for packet in info['packets']:
   s=streams[packet['stream_index']];kind=9 if s['codec_type']=='video' else 8;data=raw(packet['data']);stamp=round(float(packet['pts_time'])*1000)
   if stamp<0:continue
   f.write(struct.pack('>BqI',kind,stamp,len(data))+data);expected[kind]+=1
 run(['javac','-d',str(p),str(base/'app/ProtectMuxer.java'),str(base/'app/ProtectAudioPreroll.java'),str(base/'tests/ProtectMuxerFixture.java'),str(base/'tests/ProtectPrerollFixture.java')],capture_output=True)
 run(['java','-cp',str(p),'local.codex.portalprotectcamera.ProtectMuxerFixture',str(p/'fixture'),str(p/'baseline.flv'),'0','5000'])
 run(['java','-cp',str(p),'local.codex.portalprotectcamera.ProtectMuxerFixture',str(p/'fixture'),str(p/'extended.flv'),'30000','250'])
 def tags(data):
  at=13;out=[]
  while at<len(data):
   h=data[at:at+11];size=int.from_bytes(h[1:4],'big');stamp=int.from_bytes(h[4:7],'big')+(h[7]<<24);out.append((h[0],stamp,data[at+11:at+11+size]));at+=11+size+4+16
  assert at==len(data);return out
 baseline=tags((p/'baseline.flv').read_bytes());trial=tags((p/'extended.flv').read_bytes())
 baseline_media=[t for t in baseline if t[0] in (8,9)];trial_media=[t for t in trial if t[0] in (8,9)]
 assert len(baseline_media)==len(trial_media)
 for (k0,t0,b0),(k1,t1,b1) in zip(baseline_media,trial_media):
  assert k0==k1 and t1-t0==30000 and b0==b1
 for kind,stamp,payload in trial:
  if kind==18 and b'onClockSync' in payload:
   marker=b'\x00\x09wallClock\x00';at=payload.index(marker)+len(marker)
   assert struct.unpack('>d',payload[at:at+8])[0]==1803312000000+stamp-30000
 assert min(t for _,t,_ in trial)==30000
 assert next(i for i,(k,t,b) in enumerate(trial) if k==18 and b'onClockSync' in b)<next(i for i,(k,t,b) in enumerate(trial) if k in (8,9) and b[1]==1)
 data=(p/'extended.flv').read_bytes();at=13;standard=bytearray(data[:13]);counts=collections.Counter();last={};clock_tags=0
 while at<len(data):
  h=data[at:at+11];assert len(h)==11;kind=h[0];size=int.from_bytes(h[1:4],'big');stamp=int.from_bytes(h[4:7],'big')+(h[7]<<24);total=11+size+4
  packet=data[at:at+total];trailer=data[at+total:at+total+16];assert len(trailer)==16
  assert int.from_bytes(packet[-4:],'big')==11+size
  clock=90000 if kind==9 else 48000 if kind==8 else 11025
  assert int.from_bytes(trailer[1:4],'big')==clock
  assert int.from_bytes(trailer[12:16],'big')==(stamp*clock//1000)&0xffffffff
  if kind in (8,9) and packet[12]==1:
   assert stamp>=last.get(kind,-1);last[kind]=stamp;counts[kind]+=1
  if kind==18 and b'onClockSync' in packet:clock_tags+=1
  standard.extend(packet);at+=total+16
 assert counts[9]>140 and counts[8]>200 and clock_tags>=1
 (p/'standard.flv').write_bytes(standard)
 decoded=subprocess.run(['ffmpeg','-nostdin','-v','error','-i',str(p/'standard.flv'),'-f','null','-'],capture_output=True,text=True,timeout=20)
 assert decoded.returncode==0,decoded.stderr
 probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','stream=codec_name,width,height,sample_rate,channels','-of','json',str(p/'standard.flv')]))
 # Isolate startup suppression from timestamp correction on the same real packets.
 run(['java','-cp',str(p),'local.codex.portalprotectcamera.ProtectMuxerFixture',str(p/'fixture'),str(p/'unshifted.flv'),'30000','250','0','0'])
 run(['java','-cp',str(p),'local.codex.portalprotectcamera.ProtectMuxerFixture',str(p/'fixture'),str(p/'startup-only.flv'),'30000','250','0','750'])
 unshifted=tags((p/'unshifted.flv').read_bytes());startup=tags((p/'startup-only.flv').read_bytes())
 def media(ts,kind):return [t for t in ts if t[0]==kind and t[2][1]==1]
 assert media(startup,9)==media(unshifted,9)
 wanted_audio=[t for t in media(unshifted,8) if t[1]>=30750]
 assert media(startup,8)==wanted_audio and wanted_audio
 assert media(unshifted,8)[0][1]<30750
 assert [t for t in startup if t[0]==8 and t[2][1]==0]==[t for t in unshifted if t[0]==8 and t[2][1]==0]
 data=(p/'startup-only.flv').read_bytes();standard=bytearray(data[:13]);at=13
 while at<len(data):
  size=int.from_bytes(data[at+1:at+4],'big');total=11+size+4;standard.extend(data[at:at+total]);at+=total+16
 (p/'startup-standard.flv').write_bytes(standard)
 run(['ffmpeg','-nostdin','-v','error','-i',str(p/'startup-standard.flv'),'-f','null','-'],capture_output=True,timeout=20)
 print(json.dumps({'startup_only_test':True,'startup_drop_ms':750,'audio_timestamp_advance_ms':0,'first_raw_audio_relative_ms':wanted_audio[0][1]-30000,'video_payloads_and_timestamps_unchanged':True,'retained_audio_payloads_and_timestamps_unchanged':True,'aac_config_unchanged':True,'decoded_without_errors':True},indent=2))
 run(['java','-cp',str(p),'local.codex.portalprotectcamera.ProtectMuxerFixture',str(p/'fixture'),str(p/'shift-only.flv'),'30000','250','750','0','true'])
 shifted=tags((p/'shift-only.flv').read_bytes())
 assert media(shifted,9)==media(unshifted,9)
 expected_audio=[(k,t-750,b) for k,t,b in media(unshifted,8)]
 assert media(shifted,8)==expected_audio and len(expected_audio)>200
 shifted_config=next(t for t in shifted if t[0]==8 and t[2][1]==0)
 assert shifted_config[1]==29250 and expected_audio[0][1]>=shifted_config[1]
 assert expected_audio[0][1]<30000
 data=(p/'shift-only.flv').read_bytes();standard=bytearray(data[:13]);at=13
 while at<len(data):
  size=int.from_bytes(data[at+1:at+4],'big');total=11+size+4;standard.extend(data[at:at+total]);at+=total+16
 (p/'shift-standard.flv').write_bytes(standard)
 run(['ffmpeg','-nostdin','-v','error','-i',str(p/'shift-standard.flv'),'-f','null','-'],capture_output=True,timeout=20)
 print(json.dumps({'shift_only_test':True,'audio_timestamp_advance_ms':750,'startup_drop_ms':0,'all_startup_audio_retained':True,'all_audio_payloads_unchanged':True,'every_audio_timestamp_shifted_exactly_ms':750,'video_payloads_and_timestamps_unchanged':True,'aac_config_precedes_audio':True,'decoded_without_errors':True},indent=2))
 preroll_info=json.loads(subprocess.check_output(['java','-cp',str(p),'local.codex.portalprotectcamera.ProtectPrerollFixture',str(p/'fixture'),str(p/'preroll.flv')]))
 pre=tags((p/'preroll.flv').read_bytes());anchor=preroll_info['video_origin_ms']
 expected_video=[(k,t-anchor,b) for k,t,b in media(unshifted,9) if t>=30000+anchor]
 expected_audio=[(k,t-anchor,b) for k,t,b in media(unshifted,8) if t>=30000+anchor-200]
 assert media(pre,9)==expected_video
 assert media(pre,8)==expected_audio
 assert -200<=preroll_info['first_audio_relative_ms']<=-100
 assert next(t for t in pre if t[0]==8 and t[2][1]==0)[1]<=media(pre,8)[0][1]<media(pre,9)[0][1]
 assert next(i for i,t in enumerate(pre) if t[0]==8 and t[2][1]==1)<next(i for i,t in enumerate(pre) if t[0]==9 and t[2][1]==1)
 data=(p/'preroll.flv').read_bytes();standard=bytearray(data[:13]);at=13
 while at<len(data):
  size=int.from_bytes(data[at+1:at+4],'big');total=11+size+4;standard.extend(data[at:at+total]);at+=total+16
 (p/'preroll-standard.flv').write_bytes(standard)
 run(['ffmpeg','-nostdin','-v','error','-i',str(p/'preroll-standard.flv'),'-f','null','-'],capture_output=True,timeout=20)
 print(json.dumps({'genuine_preroll_test':True,**preroll_info,'original_payloads_and_event_timing_preserved':True,'audio_advance_ms':0,'audio_precedes_first_video':True,'decoded_without_errors':True},indent=2))
 print(json.dumps({'media_payloads_identical_to_zero_origin':True,'relative_av_timing_unchanged':True,'wall_time_mapping_unchanged':True,'first_timestamp_ms':30000,'clock_interval_ms':250,'clock_sync_before_first_frame':True,'extended_trailers_valid':True,'per_track_monotonic':True,'decoded_without_errors':True,'video_frames':counts[9],'audio_packets':counts[8],'clock_sync_tags':clock_tags,'media':probe},indent=2))
