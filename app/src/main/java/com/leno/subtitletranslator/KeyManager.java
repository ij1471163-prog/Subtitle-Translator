package com.leno.subtitletranslator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Base64;

public class KeyManager {
    private static final String PKG="com.leno.subtitletranslator";

    // Deepgram - 3 أجزاء
    private static final String D1="NDIyMGM5Nm";
    private static final String D2="VkZjc4ZTMy";
    private static final String D3="ODczNzA1NDkyYmYzMjA5MWE4NGE1MDM1Mw==";

    // AssemblyAI - 3 أجزاء
    private static final String A1="N2QxODUz";
    private static final String A2="ZDJkMWMy";
    private static final String A3="NGIxYzg2MTAxODQ4YzFlNmRhMzM=";

    // Groq - 3 أجزاء
    private static final String G1="Z3NrX2Q3NEJT";
    private static final String G2="Z1NjTWd3Q05R";
    private static final String G3="TTFEeXN6V0dkeWIzRllMM0RXaUFpNzRVRXgxRVBKUWRZVWtYUFY=";

    // Speechmatics - 3 أجزاء
    private static final String S1="Q0ZkTHJT";
    private static final String S2="Q3VlZnZn";
    private static final String S3="SlJmVU9tVEs3WVlwaTNtWWVEcEU=";

    private static boolean checkPackage(Context ctx){
        return ctx.getPackageName().equals(PKG);
    }

    private static boolean checkSignature(Context ctx){
        try{
            Signature[]sigs=ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(),PackageManager.GET_SIGNATURES)
                .signatures;
            return sigs!=null&&sigs.length>0;
        }catch(Exception e){return false;}
    }

    private static String decode(String p1,String p2,String p3){
        try{return new String(Base64.decode(p1+p2+p3,Base64.DEFAULT)).trim();}
        catch(Exception e){return "";}
    }

    private static boolean isSafe(Context ctx){
        if(!checkPackage(ctx))return false;
        if(!checkSignature(ctx))return false;
        if(!SecurityManager.isSafe(ctx))return false;
        return true;
    }

    public static String getDeepgramKey(Context ctx){
        if(!isSafe(ctx))return "";
        return decode(D1,D2,D3);
    }

    public static String getAssemblyKey(Context ctx){
        if(!isSafe(ctx))return "";
        return decode(A1,A2,A3);
    }

    public static String getGroqKey(Context ctx){
        if(!isSafe(ctx))return "";
        return decode(G1,G2,G3);
    }

    public static String getSpeechmaticsKey(Context ctx){
        if(!isSafe(ctx))return "";
        return decode(S1,S2,S3);
    }
}
