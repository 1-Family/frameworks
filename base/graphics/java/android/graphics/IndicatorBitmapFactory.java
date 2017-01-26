package android.graphics;

import android.content.res.Resources;
import android.graphics.AvoidXfermode.Mode;
import android.graphics.Bitmap.Config;
import android.util.SparseIntArray;
import android.util.Log;

/**
 * @hide
 */
public class IndicatorBitmapFactory {
    
    static final String TAG = "IndicatorBitmapFactory";
    public static final int BUSSINESS_INDICATOR = 1;
    public static final int BUSSINESS_PRIVATE_INDICATOR = 2;
    public static final int PRIVATE_INDICATOR = 3;

    private static IndicatorBitmapFactory mInstance;
    private Resources mResources;
    private static Bitmap mVerticalIndicators[];
    private static Bitmap mHorizontalIndicators[];
    private static float mShrinkFactor = 1.0F;

    private IndicatorBitmapFactory(Resources res) {
        mResources = res;
        loadIndicatorsBitmaps();
    }
    
    public static synchronized IndicatorBitmapFactory getInstance(Resources res) {
        if (mInstance == null) {
            mInstance = new IndicatorBitmapFactory(res);
        }
        return mInstance;
    }
    private void loadIndicatorsBitmaps() {
        //Log.d(TAG, "Calling loadIndicatorsBitmaps");
        if(mVerticalIndicators == null) {
            //Log.d(TAG, "loadIndicatorsBitmaps - initializing vertical array");
            mVerticalIndicators = new Bitmap[] {
                    BitmapFactory.decodeResource(mResources, com.android.internal.R.drawable.ic_blue_ic_indicator_vertical),
                    BitmapFactory.decodeResource(mResources, com.android.internal.R.drawable.ic_red_ic_indicator_vertical),
                    BitmapFactory.decodeResource(mResources, com.android.internal.R.drawable.ic_white_ic_indicator_vertical)
            };
        }
        if(mHorizontalIndicators == null) {
            //Log.d(TAG, "loadIndicatorsBitmaps - initializing horizontal array");
            mHorizontalIndicators = new Bitmap[] {
                    BitmapFactory.decodeResource(mResources, com.android.internal.R.drawable.ic_blue_ic_indicator_horizontal),
                    BitmapFactory.decodeResource(mResources, com.android.internal.R.drawable.ic_red_ic_indicator_horizontal),
                    BitmapFactory.decodeResource(mResources, com.android.internal.R.drawable.ic_white_ic_indicator_horizontal)
            };
        }
    }
    
    //The same logics as getScaledHorizontalStripe
    private Bitmap getScaledVerticalStripe(Bitmap strip,int num, int size) {
        Bitmap bmOverlay = Bitmap.createBitmap(num, size, strip.getConfig());
        Canvas canvas = new Canvas(bmOverlay);
        for (int i = 0; i < num; i++) {
            Bitmap curStrip = Bitmap.createScaledBitmap(strip, 1, size-i, false);
            canvas.drawBitmap(curStrip,num-(i+1),i, null);
        }
        return bmOverlay;
    }

    /*
    as the complete indicator is:
	   ------------
	   -----------|
	   ----------||
	            |||
	            |||
	            |||	
	  The current functions creates the horizontal part of the indicator that should be drawn like this for thickness of 3:
	  ------------
	   -----------
	    ----------	                	            
	 */
    private Bitmap getScaledHorizontalStripe(Bitmap strip,int num, int size) {
        Bitmap bmOverlay = Bitmap.createBitmap(size, num, strip.getConfig());
        Canvas canvas = new Canvas(bmOverlay);
        for (int i = 0; i < num; i++) {
            Bitmap curStrip = Bitmap.createScaledBitmap(strip, size-i, 1, false);
            canvas.drawBitmap(curStrip,0,i, null);
        }
        return bmOverlay;
    }
    
    //Not in use
    private int getStripeNum(Bitmap src) {
        int result=0;
        int size = Math.min(src.getWidth(), src.getHeight());
        SparseIntArray sizes = new SparseIntArray();
        sizes.append(36, 2);
        sizes.append(48, 3);
        sizes.append(72, 3);
        sizes.append(96, 4);
        sizes.append(144, 5);
        sizes.append(200, 5);
        int i=0;
        boolean isFound = false;
        while(i<sizes.size()-1 && !isFound){
            if (size<=(sizes.keyAt(i) + ((sizes.keyAt(i+1)-sizes.keyAt(i))/2))) {
                result= sizes.get(sizes.keyAt(i));
                isFound = true;
            }
            i++;
        }
        if (!isFound) {
            result=7;
        }
        return result;
    }   
    
    //24 is the factor. if the icon is shorter than 24px -> thickness should be 2 px.
	 //						if the icon is smaller than 48 -> thickness should be 4 px.
    private int getStripethickness(int size) {
        double factor = 24d;
        double result = (double)size / factor;
        result = Math.round(result);
        if (result<2) {
            result++;
        }
        //Log.d(TAG, "size is " + String.valueOf(result));
        return (int)result;
    }
    
    //The main method which adds the indicator (using some helping methods, declared above)
    private Bitmap getBitmapWithIndicator(Bitmap src, Bitmap horizontalStripe, Bitmap verticalStripe, Bitmap horizontalInnerStripe, Bitmap verticalInnerStripe) {
        int verticalStripeThickness = getStripethickness(src.getWidth());
        int horizontalStripeThickness = getStripethickness(src.getHeight());
        
        //new dimensions should be: orig dimensions + stripe thiickness + 1 for the white shadow
        Bitmap bmOverlay = Bitmap.createBitmap(src.getWidth()+verticalStripeThickness+1, src.getHeight()+horizontalStripeThickness+1, src.getConfig());
        Canvas canvas = new Canvas(bmOverlay);
        
        //This is the creation of the white shadow, which is always in the thickness of 1 px
        horizontalInnerStripe = Bitmap.createScaledBitmap(horizontalInnerStripe, bmOverlay.getWidth()-verticalStripeThickness, horizontalInnerStripe.getHeight(), false);
        verticalInnerStripe = Bitmap.createScaledBitmap(verticalInnerStripe, verticalInnerStripe.getWidth(), bmOverlay.getHeight()-horizontalStripeThickness , false);
        
        // This is the creation of the rest of the indicator - we need to make it sometimes more thick (contacts, pics, etc)
        horizontalStripe = getScaledHorizontalStripe(horizontalStripe,horizontalStripeThickness,bmOverlay.getWidth()-1);
        verticalStripe = getScaledVerticalStripe(verticalStripe,verticalStripeThickness,bmOverlay.getHeight());
        
			// Start putting everything together:        
        canvas.drawBitmap(src,0,horizontalStripe.getHeight()+horizontalInnerStripe.getHeight(), null);
        canvas.drawBitmap(verticalStripe, bmOverlay.getWidth()-verticalStripe.getWidth(), 0, null);
        canvas.drawBitmap(horizontalStripe, 0,0, null);
        canvas.drawBitmap(verticalInnerStripe, bmOverlay.getWidth()-verticalStripe.getWidth()-verticalInnerStripe.getWidth(), horizontalStripe.getHeight(), null);
        canvas.drawBitmap(horizontalInnerStripe, 0,horizontalStripe.getHeight(), null);
        
        return bmOverlay;
    }
    
    //Currently not in use
    private Bitmap getBitmapWithIndicator2(Bitmap src, Bitmap horizontalStripe, Bitmap verticalStripe, Bitmap horizontalInnerStripe, Bitmap verticalInnerStripe) {
        int stripeNum = getStripeNum(src);
        Bitmap bmOverlay = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
        horizontalInnerStripe = Bitmap.createScaledBitmap(horizontalInnerStripe, bmOverlay.getWidth()-stripeNum, horizontalInnerStripe.getHeight(), false);
        verticalInnerStripe = Bitmap.createScaledBitmap(verticalInnerStripe, verticalInnerStripe.getWidth(), bmOverlay.getHeight()-stripeNum , false);
        Canvas canvas = new Canvas(bmOverlay);
        //horizontalStripe = Bitmap.createScaledBitmap(horizontalStripe, bmOverlay.getWidth(), size, false);
        horizontalStripe = getScaledHorizontalStripe(horizontalStripe,stripeNum,bmOverlay.getWidth()-1);
        //verticalStripe = Bitmap.createScaledBitmap(verticalStripe, size, bmOverlay.getHeight(), false);
        verticalStripe = getScaledVerticalStripe(verticalStripe,stripeNum,bmOverlay.getHeight());
        int shrinkBy = (int) Math.round((stripeNum+horizontalInnerStripe.getHeight()) * mShrinkFactor);
        int smallWidth = src.getWidth()-shrinkBy;
        int smallHeight = src.getHeight()-shrinkBy;
        src = Bitmap.createScaledBitmap(src, smallWidth, smallHeight, false);
        canvas.drawBitmap(src,0,shrinkBy, null);
        canvas.drawBitmap(verticalStripe, bmOverlay.getWidth()-stripeNum, 0, null);
        canvas.drawBitmap(horizontalStripe, 0,0, null);
        canvas.drawBitmap(verticalInnerStripe, bmOverlay.getWidth()-stripeNum-verticalInnerStripe.getWidth(), stripeNum, null);
        canvas.drawBitmap(horizontalInnerStripe, 0,stripeNum, null);
        return bmOverlay;
    }
 	 //Currently not in use
    private Bitmap getBitmapWithIndicator3(Bitmap src, Bitmap horizontalStripe, Bitmap verticalStripe, Bitmap horizontalInnerStripe, Bitmap verticalInnerStripe) {
        int stripeNum = getStripeNum(src);
        Bitmap bmOverlay = Bitmap.createBitmap(src.getWidth()+stripeNum+1, src.getHeight()+stripeNum+1, src.getConfig());
        Canvas canvas = new Canvas(bmOverlay);
        horizontalInnerStripe = Bitmap.createScaledBitmap(horizontalInnerStripe, bmOverlay.getWidth()-stripeNum, horizontalInnerStripe.getHeight(), false);
        verticalInnerStripe = Bitmap.createScaledBitmap(verticalInnerStripe, verticalInnerStripe.getWidth(), bmOverlay.getHeight()-stripeNum , false);
        horizontalStripe = getScaledHorizontalStripe(horizontalStripe,stripeNum,bmOverlay.getWidth()-1);
        verticalStripe = getScaledVerticalStripe(verticalStripe,stripeNum,bmOverlay.getHeight());
        int shrinkBy = (int) Math.round((stripeNum+horizontalInnerStripe.getHeight()) * mShrinkFactor);
        canvas.drawBitmap(src,0,shrinkBy, null);
        canvas.drawBitmap(verticalStripe, bmOverlay.getWidth()-stripeNum, 0, null);
        canvas.drawBitmap(horizontalStripe, 0,0, null);
        canvas.drawBitmap(verticalInnerStripe, bmOverlay.getWidth()-stripeNum-verticalInnerStripe.getWidth(), stripeNum, null);
        canvas.drawBitmap(horizontalInnerStripe, 0,stripeNum, null);
        return bmOverlay;
    }
    
    public Bitmap getBusinessBitmap(Bitmap src) {
        return getBitmapWithIndicator(src,mHorizontalIndicators[0],mVerticalIndicators[0],mHorizontalIndicators[2],mVerticalIndicators[2]);
    }
    
    public Bitmap getPrivateBitmap(Bitmap src) {
        return getBitmapWithIndicator(src,mHorizontalIndicators[1],mVerticalIndicators[1],mHorizontalIndicators[2],mVerticalIndicators[2]);
    }
    
    public Bitmap getBusinessPrivateBitmap(Bitmap src) {
        return getBitmapWithIndicator(src,mHorizontalIndicators[1],mVerticalIndicators[0],mHorizontalIndicators[2],mVerticalIndicators[2]);
    }
    
    public Bitmap getBitmapWithIndicator(Bitmap src, int indicator) {
            //android.util.Log.d(TAG, "Going to add bitmap indicator to photo");
            Bitmap rv = null;
            switch(indicator) {
                case BUSSINESS_INDICATOR:
                    rv = getBusinessBitmap(src);
                    break;
                case PRIVATE_INDICATOR:
                    rv = getPrivateBitmap(src);
                    break;
                case BUSSINESS_PRIVATE_INDICATOR:
                    rv = getBusinessPrivateBitmap(src);
                    break;
            }
            return rv;
    }
}
