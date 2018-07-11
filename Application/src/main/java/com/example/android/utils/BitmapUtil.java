package com.example.android.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Matrix;
import android.util.Base64;

public class BitmapUtil {

	private static final String TAG = "BitmapUtil";

	public static Bitmap getCompressedBitmap(String filepath, int reqWidth,
			int reqHeight) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(filepath, options);

		if (reqHeight == 0 && reqWidth == 0) {
			options.inSampleSize = 1;
		} else {
			options.inSampleSize = caculateInSimpleSize(options, reqWidth,
					reqHeight);
		}
		options.inJustDecodeBounds = false;
		return BitmapFactory.decodeFile(filepath, options);
	}

	public static Bitmap getCompressedBitmap(String filepath) {
		return BitmapFactory.decodeFile(filepath);
	}

	public static byte[] getPNGImageBytesByRGBA( Bitmap bmpSrc) {
		int bmpLenght = bmpSrc.getRowBytes() * bmpSrc.getHeight();
		
		ByteBuffer srcBuffer = ByteBuffer.allocate(bmpLenght).order(ByteOrder.nativeOrder());
		bmpSrc.copyPixelsToBuffer(srcBuffer);
		byte[] rgba = srcBuffer.array();
		return rgba;
	}

	public static byte[] RGBA2ARGB(byte[] src) {
		ByteBuffer srcBuffer = ByteBuffer.wrap(src);
		ByteBuffer copyBuffer = ByteBuffer.allocate(srcBuffer.capacity());

		byte[] rgb = new byte[3];
		byte alpa;

		while (srcBuffer.hasRemaining()) {
			srcBuffer.get(rgb);
			alpa = srcBuffer.get();

			copyBuffer.put(alpa);
			copyBuffer.put(rgb);
		}

		byte[] argb = copyBuffer.array();
		return argb;
	}

	public static byte[] ARGB2RGBA(byte[] src) {
		ByteBuffer srcBuffer = ByteBuffer.wrap(src);
		ByteBuffer copyBuffer = ByteBuffer.allocate(srcBuffer.capacity());

		byte[] rgb = new byte[3];
		byte alpa;

		while (srcBuffer.hasRemaining()) {
			alpa = srcBuffer.get();
			srcBuffer.get(rgb);

			copyBuffer.put(rgb);
			copyBuffer.put(alpa);
		}

		byte[] rgba = copyBuffer.array();
		return rgba;
	}

	public static void writeBytesToFile(String filepath, byte[] data) {
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(filepath);
			fos.write(data);
			fos.flush();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static int caculateInSimpleSize(Options options, int reqWidth,
			int reqHeight) {
		int inSimpleSize = 1;
		int width = options.outWidth;
		int height = options.outHeight;
		if (reqWidth < width || reqHeight < height) {
			int widthScale = Math.round(((float) width / (float) reqWidth));
			int heightScale = Math.round(((float) height / (float) reqHeight));
			inSimpleSize = heightScale < widthScale ? heightScale : widthScale;
		}
		return inSimpleSize;
	}

	public static Bitmap getBitmapFromUrl(String url) {
		Bitmap resutBitmap = null;
		InputStream is = null;
		try {
			URL addresUrl = new URL(url);
			HttpURLConnection conn = (HttpURLConnection) addresUrl
					.openConnection();
			conn.setReadTimeout(5 * 1000);
			conn.setConnectTimeout(5 * 1000);
			if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
				is = conn.getInputStream();
				resutBitmap = BitmapFactory.decodeStream(is);
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return resutBitmap;
	}

	public static Bitmap getRotateBitmap(int type, String bitmapPath) {
		Bitmap bitmap = BitmapFactory.decodeFile(bitmapPath);
		Bitmap resultBitmap = bitmap;
		int height = bitmap.getHeight();
		int width = bitmap.getWidth();
		switch (type) {
		case 1:
			if (height < width) {
				Matrix mmMatrix = new Matrix();
				mmMatrix.postRotate(90f);
				resultBitmap = Bitmap.createBitmap(bitmap, 0, 0,
						bitmap.getWidth(), bitmap.getHeight(), mmMatrix, true);
			}
			break;
		case 2:
			if (height > width) {
				Matrix mmMatrix = new Matrix();
				mmMatrix.postRotate(90f);
				resultBitmap = Bitmap.createBitmap(bitmap, 0, 0,
						bitmap.getWidth(), bitmap.getHeight(), mmMatrix, true);
			}
			break;

		default:
			break;
		}
		return resultBitmap;
	}

	public static boolean saveBitmapToFile(Bitmap bitmap, String filename) {
		return saveBitmapToFile(bitmap, filename, 100);
	}

	public static boolean saveBitmapToFile(Bitmap bitmap, String filename,
			int quality) {
		OutputStream os = null;
		try {
			File file = new File(filename);
			if (file.exists()) {
				file.delete();
			}
			os = new FileOutputStream(file);
			return bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (os != null) {
				try {
					os.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		return false;
	}

	public static Bitmap decodeResource(Context context, int resourseId) {
		BitmapFactory.Options opt = new BitmapFactory.Options();
		opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
		opt.inPurgeable = true;
		opt.inInputShareable = true;
		InputStream is = context.getResources().openRawResource(resourseId);
		return BitmapFactory.decodeStream(is, null, opt);
	}

	public static Bitmap decodeBitmapFromAssets(Context context, String resName) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		options.inPurgeable = true;
		options.inInputShareable = true;
		InputStream in = null;
		try {
			in = context.getAssets().open(resName);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return BitmapFactory.decodeStream(in, null, options);
	}

	public static void recycleBitmap(Bitmap b) {
		if (b != null && !b.isRecycled()) {
			b.recycle();
			b = null;
			System.gc();
		}
	}

	public static byte[] fileToBytes(File file) {
		if (file == null) {
			return null;
		}
		FileInputStream fis = null;
		ByteArrayOutputStream baos = null;
		try {
			fis = new FileInputStream(file);
			baos = new ByteArrayOutputStream();
			byte[] buffer = new byte[1000];
			for (int n = 0; (n = fis.read(buffer)) > 0;) {
				baos.write(buffer, 0, n);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (baos != null) {
				try {
					baos.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return baos.toByteArray();
	}

	public static byte[] bitmapToBytes(Bitmap bm) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		bm.compress(Bitmap.CompressFormat.PNG, 100, baos);
		return baos.toByteArray();
	}

	public static Bitmap bytesToBitmap(byte[] b) {
		if (b.length != 0) {
			return BitmapFactory.decodeByteArray(b, 0, b.length);
		}
		return null;
	}

	public static String bitmaptoString(Bitmap bitmap) {
		String string = null;
		ByteArrayOutputStream bStream = new ByteArrayOutputStream();
		bitmap.compress(CompressFormat.PNG, 100, bStream);
		byte[] bytes = bStream.toByteArray();
		string = Base64.encodeToString(bytes, Base64.DEFAULT);
		return string;
	}

	public static Bitmap stringToBitmap(String string) {
		Bitmap bitmap = null;
		try {
			byte[] bitmapArray;
			bitmapArray = Base64.decode(string, Base64.DEFAULT);
			bitmap = BitmapFactory.decodeByteArray(bitmapArray, 0,
					bitmapArray.length);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return bitmap;
	}

}
