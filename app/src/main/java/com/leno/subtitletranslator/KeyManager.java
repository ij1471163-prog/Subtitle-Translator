package com.leno.subtitletranslator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Base64;
import java.util.Arrays;
public class KeyManager {
    private static final String PKG="com.leno.subtitletranslator";
    private static final String G1="OS01ATpp";
    private static final String G2="ahwNOQ09";
    private static final String G3="EzkpHRAP";
    private static final String G4="E28aPS0k";
    private static final String G5="CRk6JzxtGAcSbRoJNx83aWoLGyZvGw4UDzoHCzUGDgg=";
    private static final String D1="amxsbj1n";
    private static final String D2="aDs6OGlm";
    private static final String D3="O21sZmlt";
    private static final String D4="aW5ramdsPDht";
    private static final String D5="bG5nbz9maj9rbm1rbQ==";
    private static final String S1="HRg6Eiw";
    private static final String S2="NHSs7OCg";
    private static final String S3="5FAw4CxEz";
    private static final String S4="ChVpBwcu";
    private static final String S5="N20zBzsaLhs=";
    private static final String A1="aTpvZmtt";
    private static final String A2="Omw6bz1s";
    private static final String A3="ajxvPWZo";
    private static final String A4="bm9vZmpm";
    private static final String A5="PW87aDo/bW0=";
    private static int getXK(){
        int a=PKG.length();
        int b=AppConstants.VERSION.length()+AppConstants.MAX_RETRY;
        int d=(int)(AppConstants.TIMEOUT/100)%50;
        int e=AppUtils.getPart1();
        int f=AppUtils.getPart2();
        int g=AppUtils.getPart3();
        return ((a*3)^(b*7)^d^e^f^g^0x1F)&0xFF;
    }
    private static boolean checkPackage(Context ctx){return ctx.getPackageName().equals(PKG);}
    private static boolean checkSignature(Context ctx){
        try{Signature[]sigs=ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),PackageManager.GET_SIGNATURES).signatures;return sigs!=null&&sigs.length>0;}
        catch(Exception e){return false;}}
    private static String decode(String p1,String p2,String p3,String p4,String p5){
        try{
            byte[]b=Base64.decode(p1+p2+p3+p4+p5,Base64.DEFAULT);
            int xk=getXK();byte[]r=new byte[b.length];
            for(int i=0;i<b.length;i++)r[i]=(byte)(b[i]^xk);
            String result=new String(r).trim();
            Arrays.fill(b,(byte)0);Arrays.fill(r,(byte)0);
            return result;}catch(Exception e){return "";}}
    private static boolean isSafe(Context ctx){
        if(!checkPackage(ctx))return "";
        if(!checkSignature(ctx))return "";
        if(!SecurityManager.isSafe(ctx))return "";
        return true;}
    public static String getGroqKey(Context ctx){if(!isSafe(ctx))return "";return decode(G1,G2,G3,G4,G5);}
    public static String getDeepgramKey(Context ctx){if(!isSafe(ctx))return "";return decode(D1,D2,D3,D4,D5);}
    public static String getSpeechmaticsKey(Context ctx){if(!isSafe(ctx))return "";return decode(S1,S2,S3,S4,S5);}
    public static String getAssemblyKey(Context ctx){if(!isSafe(ctx))return "";return decode(A1,A2,A3,A4,A5);}
}