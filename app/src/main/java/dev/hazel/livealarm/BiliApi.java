package dev.hazel.livealarm;

import org.json.*;
import java.net.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

public final class BiliApi {
    public static final class Snapshot {
        public int status;public long start,checkedAt;public String title,source;public long latency;
        public JSONObject json(){JSONObject j=new JSONObject();Prefs.put(j,"status",status);Prefs.put(j,"start",start);Prefs.put(j,"checkedAt",checkedAt);Prefs.put(j,"title",title);Prefs.put(j,"source",source);Prefs.put(j,"latency",latency);return j;}
    }
    public static final class ApiException extends IOException {public final boolean rateLimited;ApiException(String message,boolean rate){super(message);rateLimited=rate;}}
    public Snapshot fetch()throws IOException{
        long began=System.nanoTime();JSONObject d;String source="room/get_info";
        try {d=request("https://api.live.bilibili.com/room/v1/Room/get_info?room_id="+Prefs.ROOM);}
        catch(ApiException e){if(e.rateLimited)throw e;source="room_init";d=request("https://api.live.bilibili.com/room/v1/Room/room_init?id="+Prefs.ROOM);}
        catch(IOException e){source="room_init";d=request("https://api.live.bilibili.com/room/v1/Room/room_init?id="+Prefs.ROOM);}
        Snapshot s=parse(d,source);s.checkedAt=System.currentTimeMillis();s.latency=(System.nanoTime()-began)/1000000;return s;
    }
    static Snapshot parse(JSONObject d,String source)throws ApiException{
        if(d.optLong("uid",-1)!=Prefs.UID || d.optLong("room_id",-1)!=Prefs.ROOM)throw new ApiException("直播间身份校验未通过，已暂停本次提醒",false);
        if(!d.has("live_status"))throw new ApiException("B 站返回缺少直播状态",false);
        int status=d.optInt("live_status",-1);if(status<0||status>2)throw new ApiException("B 站返回未知直播状态",false);
        Snapshot s=new Snapshot();s.status=status;s.title=d.optString("title","");s.source=source;
        Object value=d.opt("live_time");
        try{
            if(value instanceof Number){long t=((Number)value).longValue();s.start=t>100000000000L?t:t*1000L;}
            else if(value instanceof String && !((String)value).startsWith("0000")){
                String text=(String)value;
                if(text.matches("[0-9]+")){long t=Long.parseLong(text);s.start=t>100000000000L?t:t*1000L;}
                else s.start=LocalDateTime.parse(text,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            }
        }catch(Exception ignored){s.start=0;}
        if(s.start<1000000000000L)s.start=0;
        return s;
    }
    private JSONObject request(String address)throws IOException{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
        c.setRequestProperty("Referer","https://live.bilibili.com/"+Prefs.ROOM);
        c.setRequestProperty("Accept","application/json");c.setUseCaches(false);
        try{
            int status=c.getResponseCode();if(status==412||status==429)throw new ApiException("B 站暂时限制访问，正在延长重试间隔",true);
            if(status!=200)throw new ApiException("B 站接口暂不可用（HTTP "+status+"）",false);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            try(InputStream in=c.getInputStream()){while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>1048576)throw new IOException("响应过大");}}
            JSONObject root=new JSONObject(bytes.toString("UTF-8"));int code=root.optInt("code",-999);
            if(code!=0)throw new ApiException("B 站接口返回 "+code+"，等待重试",code==-352||code==-412||code==-509);
            JSONObject d=root.optJSONObject("data");if(d==null)throw new ApiException("B 站接口数据为空",false);return d;
        }catch(JSONException e){throw new ApiException("B 站接口格式变化，暂无法确认开播",false);}finally{c.disconnect();}
    }
}
