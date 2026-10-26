package local.codex.portalprotectcamera;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;
/** Small RFC6455 client: masked client frames, bounded reads, ping/pong and fragments. */
final class ProtectWebSocket implements Closeable {
  final SSLSocket socket;final InputStream in;final OutputStream out;final SecureRandom random=new SecureRandom();
  long serverDateMs;int count;
  ProtectWebSocket(SSLContext tls,String host,Map<String,String> headers)throws Exception{
    socket=(SSLSocket)tls.getSocketFactory().createSocket();try{socket.connect(new InetSocketAddress(host,7442),5000);socket.setSoTimeout(45000);socket.startHandshake();
    in=new BufferedInputStream(socket.getInputStream(),16384);out=new BufferedOutputStream(socket.getOutputStream(),16384);
    byte[] nonce=new byte[16];random.nextBytes(nonce);String key=Base64.getEncoder().encodeToString(nonce);
    StringBuilder req=new StringBuilder("GET /camera/1.0/ws HTTP/1.1\r\nHost: "+host+":7442\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Protocol: secure_transfer\r\n");
    for(Map.Entry<String,String> e:headers.entrySet())req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");req.append("\r\n");out.write(req.toString().getBytes(StandardCharsets.US_ASCII));out.flush();
    String status=line();if(!status.startsWith("HTTP/1.1 101 "))throw new IOException("WebSocket HTTP "+status.split(" ")[1]);
    Map<String,String> response=new HashMap<>();for(String l;(l=line()).length()>0;){int colon=l.indexOf(':');if(colon>0)response.put(l.substring(0,colon).toLowerCase(Locale.US),l.substring(colon+1).trim());}
    String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
    if(!accept.equals(response.get("sec-websocket-accept"))||(response.containsKey("sec-websocket-protocol")&&!"secure_transfer".equals(response.get("sec-websocket-protocol"))))throw new IOException("Invalid WebSocket upgrade");
    String date=response.get("date");if(date!=null){java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);serverDateMs=f.parse(date).getTime()+500;}
    }catch(Exception failure){try{socket.close();}catch(Exception ignored){}throw failure;}
  }
  String line()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int n;while((n=in.read())>=0){if(n==10)break;if(n!=13)b.write(n);if(b.size()>16384)throw new IOException("Oversized HTTP header");}if(n<0)throw new EOFException();return b.toString("US-ASCII");}
  int read()throws IOException{int n=in.read();if(n<0)throw new EOFException();return n;}
  synchronized void send(int opcode,byte[] data)throws IOException{
    out.write(0x80|opcode);if(data.length<126)out.write(0x80|data.length);else if(data.length<=65535){out.write(0xfe);out.write(data.length>>8);out.write(data.length);}else{out.write(0xff);for(int i=7;i>=0;i--)out.write((int)(((long)data.length)>>(8*i))&255);}
    byte[] mask=new byte[4];random.nextBytes(mask);out.write(mask);byte[] encoded=new byte[data.length];for(int i=0;i<data.length;i++)encoded[i]=(byte)(data[i]^mask[i&3]);out.write(encoded);out.flush();
  }
  void text(String text)throws IOException{send(1,text.getBytes(StandardCharsets.UTF_8));}
  String receive()throws IOException{
    ByteArrayOutputStream message=new ByteArrayOutputStream();int type=0;
    while(true){int first=read(),second=read(),opcode=first&15;boolean fin=(first&128)!=0;if((first&112)!=0||(second&128)!=0)throw new IOException("Unsupported WebSocket frame");
      long len=second&127;if(len==126)len=(read()<<8)|read();else if(len==127){len=0;for(int i=0;i<8;i++)len=(len<<8)|read();}
      if(len<0||len>2*1024*1024||message.size()+len>2*1024*1024)throw new IOException("Oversized control message");
      byte[] data=new byte[(int)len];int at=0;while(at<data.length){int n=in.read(data,at,data.length-at);if(n<0)throw new EOFException();at+=n;}
      if(opcode>=8){if(!fin||len>125)throw new IOException("Invalid control frame");if(opcode==8)throw new EOFException("WebSocket closed");if(opcode==9)send(10,data);continue;}
      if(opcode==1||opcode==2){if(type!=0)throw new IOException("Unexpected text frame");type=opcode;}else if(opcode!=0||type==0)throw new IOException("Expected text message");
      message.write(data);if(fin)return message.toString("UTF-8");
    }
  }
  public void close(){try{socket.close();}catch(IOException ignored){}}
}
