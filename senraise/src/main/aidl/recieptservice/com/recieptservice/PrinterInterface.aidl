package recieptservice.com.recieptservice;

// Present in our copy and not the vendor's: the interface takes a Bitmap but
// never imported it, which aidl only tolerates in some toolchain versions.
import android.graphics.Bitmap;


interface PrinterInterface {
     void printEpson(in byte []data);
     String getServiceVersion();
     void printText(String text);
     void printBitmap(in Bitmap pic);
     void printBarCode(String data, int symbology, int height, int width);
     void printQRCode(String data, int modulesize, int errorlevel);
     void setAlignment(int alignment);
     void setTextSize(float textSize);
     void nextLine(int line);
     void printTableText(in String[] text,in int []weight,in int []alignment);
     void setTextBold(boolean bold);
}
