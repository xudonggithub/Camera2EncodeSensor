package com.example.android.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

public class FileUtils {

	public static boolean copyFile(File sourceFile, File targetFile,
			FileOperationCompletedListener fileOperationCompletedListener)
			throws IOException {
		BufferedInputStream inBuff = null;
		BufferedOutputStream outBuff = null;
		int totalSize = 0;
		try {
			inBuff = new BufferedInputStream(new FileInputStream(sourceFile));

			outBuff = new BufferedOutputStream(new FileOutputStream(targetFile));

			byte[] b = new byte[1024 * 5];
			int len;
			while ((len = inBuff.read(b)) != -1) {
				totalSize += len;
				outBuff.write(b, 0, len);
			}
			outBuff.flush();
		} finally {
			if (inBuff != null)
				inBuff.close();
			if (outBuff != null)
				outBuff.close();
		}
		if (fileOperationCompletedListener != null)
			return fileOperationCompletedListener.onFileOperationCompleted(sourceFile,
					targetFile, totalSize);
		return true;
	}

	public static boolean copyDirectiory(String sourceDir, String targetDir, FileOperationCompletedListener fileOperationCompletedListener)
			throws IOException {
		(new File(targetDir)).mkdirs();
		File[] file = (new File(sourceDir)).listFiles();
		for (int i = 0; i < file.length; i++) {
			if (file[i].isFile()) {
				File sourceFile = file[i];
				File targetFile = new File(
						new File(targetDir).getAbsolutePath() + File.separator
								+ file[i].getName());
				boolean res = copyFile(sourceFile, targetFile, fileOperationCompletedListener);
				if(!res)
					return res;
			}
			if (file[i].isDirectory()) {
				String dir1 = sourceDir + "/" + file[i].getName();
				String dir2 = targetDir + "/" + file[i].getName();
				boolean res = copyDirectiory(dir1, dir2, fileOperationCompletedListener);
				if(!res)
					return res;
			}
		}
		return true;
	}

	/**
	 * 
	 * @param srcFileName
	 * @param destFileName
	 * @param srcCoding
	 * @param destCoding
	 * @throws IOException
	 */
	private static void copyFile(File srcFileName, File destFileName,
			String srcCoding, String destCoding) throws IOException {// ���ļ�ת��ΪGBK�ļ�
		BufferedReader br = null;
		BufferedWriter bw = null;
		try {
			br = new BufferedReader(new InputStreamReader(new FileInputStream(
					srcFileName), srcCoding));
			bw = new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(destFileName), destCoding));
			char[] cbuf = new char[1024 * 5];
			int len = cbuf.length;
			int off = 0;
			int ret = 0;
			while ((ret = br.read(cbuf, off, len)) > 0) {
				off += ret;
				len -= ret;
			}
			bw.write(cbuf, 0, off);
			bw.flush();
		} finally {
			if (br != null)
				br.close();
			if (bw != null)
				bw.close();
		}
	}

	/**
	 * 
	 * @param filepath
	 * @throws IOException
	 */
	public static void del(String filepath) throws IOException {
		File f = new File(filepath);
		if (f.exists() && f.isDirectory()) {
			if (f.listFiles().length == 0) {
				f.delete();
			} else {
				File delFile[] = f.listFiles();
				int i = f.listFiles().length;
				for (int j = 0; j < i; j++) {
					if (delFile[j].isDirectory()) {
						del(delFile[j].getAbsolutePath());
					}
					delFile[j].delete();
				}
			}
		}
	}

	public static boolean copyDirectionIteratively(String srcFile,
			String desFile, FileOperationCompletedListener fileOperationCompletedListener) {
		try {
			(new File(desFile)).mkdirs();
			File[] file = (new File(srcFile)).listFiles();
			for (int i = 0; i < file.length; i++) {
				if (file[i].isFile()) {
					boolean res = copyFile(file[i], new File(desFile + file[i].getName()), fileOperationCompletedListener);
					if(!res)
						return res;
				}
				if (file[i].isDirectory()) {
					String sourceDir = srcFile + File.separator
							+ file[i].getName();
					String targetDir = desFile + File.separator
							+ file[i].getName();
					boolean res = copyDirectiory(sourceDir, targetDir, fileOperationCompletedListener);
					if(!res)
						return res;
				}
			}
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	public static int getFileCountInteratively(String fileName)
	{
		int count = 0;
		if(fileName == null)
			return 0;
		File file = new File(fileName);
		if(!file.exists())
			return 0;
		if(file.isDirectory())
		{
			File[] subFiles = file.listFiles();
			if(subFiles != null)
			{
				for (int i = 0; i < subFiles.length; i++) {
					if(subFiles[i].isDirectory())
						count += getFileCountInteratively(subFiles[i].getPath());
					else
						count += 1;
				}
			}
		}
		else
			return 1;
		
		return count;
	}
	
	public static boolean deleteFilesInteratively(String fileName)
	{
		File file = new File(fileName);
		boolean res = true;
		if(file.isDirectory())
		{
			File[] subFiles = file.listFiles();
			if(subFiles != null)
			{
				for (int i = 0; i < subFiles.length; i++) {
					if(subFiles[i].isDirectory())
					{
						res = deleteFilesInteratively(subFiles[i].getPath());
						if(!res)
							return res;
					}
					else
					{
						res = subFiles[i].delete();
						if(!res)
							return res;
					}
				}
			}
		}
		res = file.delete();
//		System.out.println("delete:"+file.getPath());
		return res;
	}
	
	public interface FileOperationCompletedListener
	{
		/**
		 * 
		 * @param sourceFile
		 * @param targetFile
		 * @param fileSize
		 * @return false:stop copying 
		 */
		public boolean onFileOperationCompleted(File sourceFile, File targetFile, int fileSize);
	}
	
	public static String formetFileSize(long fileS)
	{
		DecimalFormat df = new DecimalFormat("#.00");
		String fileSizeString = "";
		if (fileS < 1024) {
			fileSizeString = df.format((double) fileS) + "B";
		} else if (fileS < 1048576) {
			fileSizeString = df.format((double) fileS / 1024) + "K";
		} else if (fileS < 1073741824) {
			fileSizeString = df.format((double) fileS / 1048576) + "M";
		} else {
			fileSizeString = df.format((double) fileS / 1073741824) + "G";
		}
		return fileSizeString;
	}
	
	public static int getFileSize(String path)
	{
		File file = new File(path);
		int size = 0;
		if (file.exists()) {
			FileInputStream fis = null;
			try {
				fis = new FileInputStream(file);
				size = fis.available();
				fis.close();
			} catch (IOException e) {
			}
		}
		return size;
	}
	
	public static boolean saveBitmap(String path, Bitmap bmp, Bitmap.CompressFormat format) throws IOException
 {
		boolean ret = true;
		File f = new File(path);
		f.getParentFile().mkdirs();
		f.createNewFile();
		FileOutputStream fOut = null;
		try {
			fOut = new FileOutputStream(f);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			ret = false;
		}
		bmp.compress(format, 100, fOut);
		try {
			fOut.flush();
		} catch (IOException e) {
			e.printStackTrace();
			ret = false;
		}
		try {
			fOut.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return ret;
	}
	
	public static boolean copyRawResource2File(Context context, int resurceId, String path, boolean bForceCover)
	{
		boolean succuss = true;
		File desFile = new File(path);
		if (!bForceCover && desFile.exists())
			return succuss;
		try {
			InputStream in = context.getResources().openRawResource(resurceId);
			FileOutputStream fos = new FileOutputStream(new File(path));
			byte[] buffer = new byte[10240];
			int count = 0;
			while ((count = in.read(buffer) )>  0) {
				fos.write(buffer, 0, count);
			}
			in.close();
			fos.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		return succuss;
	}
	
	public static void saveRawData(byte[] data, String fileName) {
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(new File(fileName));
			fos.write(data);
			fos.close();
		}catch (Exception e) {
			if(fos != null) {
				try {
					fos.close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				e.printStackTrace();
			}
		}
	}
	
	public static void unZipFiles(File zipFile,String descDir)throws IOException{  
        File pathFile = new File(descDir);  
        if(!pathFile.exists()){  
            pathFile.mkdirs();  
        }  
        ZipFile zip = new ZipFile(zipFile);  
        List<String> subZipFiles = new ArrayList<String>();
		for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries
				.hasMoreElements();) {
			ZipEntry entry = (ZipEntry) entries.nextElement();
			String zipEntryName = entry.getName();//end with '\' if for directory
			InputStream in = zip.getInputStream(entry);
			String outPath = (descDir+File.separator + zipEntryName).replaceAll("\\*", File.separator);
			File outFile = new File(outPath);
			File file = new File(outPath.substring(0, outPath.lastIndexOf(File.separator)));//get itself for dir,get parent dir for file
			if (!file.exists()) {
				file.mkdirs();
			}
			
			if (outFile.isDirectory()) {
				continue;
			}
			else if(outFile.getName().toLowerCase().endsWith(".zip")) {
				subZipFiles.add(outFile.getPath());
			}
			OutputStream out = new FileOutputStream(outFile);
			byte[] buf1 = new byte[1024];
			int len;
			while ((len = in.read(buf1)) > 0) {
				out.write(buf1, 0, len);
			}
			in.close();
			out.close();
		}
		zip.close();
		
		if(subZipFiles.size() > 0) {
			for (String subZipFile:subZipFiles) {
				File subZip = new File(subZipFile);
				String desPath = subZip.getParentFile().getPath();
				unZipFiles(subZip,desPath);
				subZip.delete();
			}
		}
    }  
}
