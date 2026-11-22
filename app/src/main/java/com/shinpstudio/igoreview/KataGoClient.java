package com.shinpstudio.igoreview;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class KataGoClient {
    static final class Candidate {
        final int index,visits;
        final double winrate,score;
        Candidate(int index,int visits,double winrate,double score){
            this.index=index;this.visits=visits;this.winrate=winrate;this.score=score;
        }
    }
    static final class Result {
        final List<Candidate> moves;
        final double winrate,score;
        Result(List<Candidate> moves,double winrate,double score){
            this.moves=moves;this.winrate=winrate;this.score=score;
        }
    }
    static Result analyze(AppSettings s,BoardRecognizer.Position p,int turn)throws Exception{
        if(turn!=BoardRecognizer.BLACK&&turn!=BoardRecognizer.WHITE)
            throw new IllegalArgumentException("手番が不明です");
        String endpoint=s.endpoint();
        if(!endpoint.startsWith("http://")&&!endpoint.startsWith("https://"))
            throw new IllegalArgumentException("解析サーバーのURLが不正です");
        JSONObject request=new JSONObject();JSONArray board=new JSONArray();
        for(int r=0;r<9;r++){
            StringBuilder line=new StringBuilder();
            for(int c=0;c<9;c++){
                int stone=p.stones[r*9+c];
                line.append(stone==BoardRecognizer.BLACK?'B':stone==BoardRecognizer.WHITE?'W':'.');
            }
            board.put(line.toString());
        }
        request.put("board",board).put("toPlay",turn==BoardRecognizer.BLACK?"B":"W")
            .put("maxVisits",s.visits()).put("candidates",s.candidates());
        HttpURLConnection conn=(HttpURLConnection)new URL(endpoint+"/analyze").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
        if(!s.token().isEmpty())conn.setRequestProperty("X-Review-Token",s.token());
        conn.setConnectTimeout(3000);conn.setReadTimeout(20000);conn.setDoOutput(true);
        try{
            byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
            int status=conn.getResponseCode();
            if(status!=200)throw new IllegalStateException("KataGoサーバー HTTP "+status);
            StringBuilder payload=new StringBuilder();
            try(BufferedReader in=new BufferedReader(new InputStreamReader(
                    conn.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=in.readLine())!=null)payload.append(line);
            }
            JSONObject result=new JSONObject(payload.toString());
            List<Candidate> moves=new ArrayList<>();
            JSONArray items=result.getJSONArray("candidates");
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);
                int index=parseCoordinate(item.getString("move"));
                if(index<0||p.stones[index]!=BoardRecognizer.EMPTY)continue;
                moves.add(new Candidate(index,item.optInt("visits",0),
                    item.optDouble("winrate",.5),item.optDouble("scoreLead",0)));
            }
            return new Result(moves,result.optDouble("rootWinrate",.5),
                result.optDouble("rootScoreLead",0));
        }finally{conn.disconnect();}
    }
    static int parseCoordinate(String s){
        if(s==null||s.length()<2)return -1;
        int col="ABCDEFGHJ".indexOf(Character.toUpperCase(s.charAt(0)));
        int row;
        try{row=Integer.parseInt(s.substring(1));}catch(NumberFormatException e){return -1;}
        return col<0||row<1||row>9?-1:(9-row)*9+col;
    }
}
