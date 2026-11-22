package com.shinpstudio.igoreview;
import android.graphics.Rect;
import android.media.Image;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** 9x9 board detector. Frame edges MUST align with the four outermost intersections. */
final class BoardRecognizer {
    static final int EMPTY=0,BLACK=1,WHITE=2,UNKNOWN=3;
    private static final int[][] OFFSETS={{33,0},{-33,0},{0,33},{0,-33},
        {23,23},{-23,23},{23,-23},{-23,-23}};
    static final class Position {
        final int[] stones;
        final int last,turn;
        Position(int[] stones,int last,int turn){this.stones=stones;this.last=last;this.turn=turn;}
        boolean sameStones(Position p){return p!=null&&Arrays.equals(stones,p.stones);}
        boolean same(Position p){return sameStones(p)&&turn==p.turn;}
    }
    static Position read(Image image,Rect frame){
        if(frame.width()<240||frame.height()<240||Math.abs(frame.width()-frame.height())>frame.width()*.07f
            ||frame.left<0||frame.top<0||frame.right>=image.getWidth()||frame.bottom>=image.getHeight())
            throw new IllegalArgumentException("盤面枠を9路の外周交点に合わせてください");
        Image.Plane plane=image.getPlanes()[0];
        ByteBuffer data=plane.getBuffer();
        int stride=plane.getRowStride(),pixelStride=plane.getPixelStride();
        float stepX=frame.width()/8f,stepY=frame.height()/8f;
        int[] board=new int[81];int markers=0,markedIndex=-1,markedColor=UNKNOWN,count=0;
        for(int row=0;row<9;row++) for(int col=0;col<9;col++){
            int cx=Math.round(frame.left+stepX*col),cy=Math.round(frame.top+stepY*row);
            int[] votes=new int[4];
            for(int[] delta:OFFSETS) votes[classify(pixel(data,stride,pixelStride,
                cx+Math.round(stepX*delta[0]/100f),cy+Math.round(stepY*delta[1]/100f)))]++;
            int color=votes[BLACK]>=6?BLACK:votes[WHITE]>=6?WHITE:votes[EMPTY]>=6?EMPTY:UNKNOWN;
            if(color==UNKNOWN) throw new IllegalArgumentException("石の認識に失敗しました。枠を調整してください");
            board[row*9+col]=color;
            if(color!=EMPTY){
                count++;
                if(marked(data,stride,pixelStride,cx,cy,Math.min(stepX,stepY),color)){
                    markers++;markedIndex=row*9+col;markedColor=color;
                }
            }
        }
        int turn=markers==1?(markedColor==BLACK?WHITE:BLACK):(count==0?BLACK:UNKNOWN);
        return new Position(board,markers==1?markedIndex:-1,turn);
    }
    static int inferTurn(Position previous,Position current){
        if(current.turn!=UNKNOWN)return current.turn;
        if(previous==null)return UNKNOWN;
        if(current.sameStones(previous))return previous.turn;
        int newBlack=0,newWhite=0;
        for(int i=0;i<81;i++){
            if(previous.stones[i]==EMPTY&&current.stones[i]==BLACK)newBlack++;
            if(previous.stones[i]==EMPTY&&current.stones[i]==WHITE)newWhite++;
        }
        if(newBlack==1&&newWhite==0)return WHITE;
        if(newWhite==1&&newBlack==0)return BLACK;
        return UNKNOWN;
    }
    private static boolean marked(ByteBuffer data,int stride,int ps,int cx,int cy,float size,int color){
        int opposite=0;
        for(int i=0;i<16;i++){
            double angle=Math.PI*2*i/16;
            int x=cx+Math.round((float)Math.cos(angle)*size*.24f);
            int y=cy+Math.round((float)Math.sin(angle)*size*.24f);
            int v=pixel(data,stride,ps,x,y);
            int r=(v>>16)&255,g=(v>>8)&255,b=v&255;
            if((color==WHITE&&Math.max(r,Math.max(g,b))<110)
                ||(color==BLACK&&Math.min(r,Math.min(g,b))>165))opposite++;
        }
        return opposite>=8;
    }
    private static int classify(int rgb){
        int r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
        int min=Math.min(r,Math.min(g,b)),max=Math.max(r,Math.max(g,b));
        if(max<125&&max-min<70)return BLACK;
        if(min>160&&max-min<56)return WHITE;
        if(r>145&&r-b>34&&r-g>8)return EMPTY;
        return UNKNOWN;
    }
    private static int pixel(ByteBuffer data,int stride,int ps,int x,int y){
        int k=y*stride+x*ps;
        return ((data.get(k)&255)<<16)|((data.get(k+1)&255)<<8)|(data.get(k+2)&255);
    }
}
