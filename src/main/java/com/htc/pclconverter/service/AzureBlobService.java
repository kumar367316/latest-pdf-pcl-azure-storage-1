package com.htc.pclconverter.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.htc.pclconverter.exception.ApplicationException;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.spire.pdf.FileFormat;
import com.spire.pdf.PdfDocument;

/**
 * @author kumar.charanswain
 *
 */

@Service
public class AzureBlobService {

	@Autowired
	BlobClientBuilder client;

	@Value("${blob.connection-string}")
	private String storageConnectionString;

	@Value("${blob.dest.connection-string}")
	private String destStorageConnectionString;

	@Value("${blob.connection.accontName.accountKey}")
	private String connectionNameKey;

	@Value("${blob.container.name}")
	private String containerName;

	@Value("${file.root.location}")
	private String fileRootLocation;

	@Value("${file.output.location}")
	private String fileOutputLocation;

	public String upload(MultipartFile[] files) {
		String result = "successfully upload document";
		if (files != null && files.length > 0) {
			try {
				// implement your own file name logic.
				for (MultipartFile file : files) {
					String fileName = file.getOriginalFilename();
					client.blobName(fileName).buildClient().upload(file.getInputStream(), file.getSize());
				}

			} catch (Exception exception) {
				if (exception.getMessage().contains("BlobAlreadyExists"))
					result = "The specified document already exists";
				else
					result = "Error in upload document";
				System.out.println("Exception:" + exception.getMessage());
			}
		} else {
			throw new ApplicationException("file is empty:Please add file for process", HttpStatus.NOT_FOUND.value());
		}
		return result;
	}

	@SuppressWarnings("deprecation")
	public String getFile() throws IOException {

		String result = "merge pdf and covert into pcl file successfully";

		BlobServiceClient blobServiceClient = new BlobServiceClientBuilder().connectionString(connectionNameKey)
				.buildClient();
		BlobServiceClient destblobServiceClient = new BlobServiceClientBuilder()
				.connectionString(destStorageConnectionString).buildClient();
		BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("htc-pcl-product-images");
		BlobContainerClient destcontainer = destblobServiceClient
				.getBlobContainerClient("htc-pcl-product-images-backup");
		try {
			CloudStorageAccount account = CloudStorageAccount.parse(connectionNameKey);
			CloudBlobClient serviceClient = account.createCloudBlobClient();
			File targetFile = new File(fileRootLocation);
			if (!targetFile.exists()) {
				targetFile.mkdir();
			}
			int pageCount = 0;
			PdfDocument doc = new PdfDocument();
			ConcurrentHashMap<Integer, List<String>> map = new ConcurrentHashMap<Integer, List<String>>();
			CloudBlobContainer container = serviceClient.getContainerReference(containerName);

			container.createIfNotExists();
			Iterable<ListBlobItem> blobs = container.listBlobs();
			for (ListBlobItem blob : blobs) {
				String[] str1 = blob.getUri().toString().split("/");
				File updateFile = new File(targetFile.getAbsolutePath() + "\\" + str1[4].replace("%20", " "));
				String fileExtension = FilenameUtils.getExtension(updateFile.toString());
				if (fileExtension.equals("pcl"))
					continue;
				CloudBlockBlob cloudBlob = (CloudBlockBlob) blob;
				cloudBlob.downloadToFile(updateFile.toString());
				doc.loadFromFile(updateFile.getAbsolutePath());
				pageCount = doc.getPages().getCount();
				if (map.containsKey(pageCount)) {
					List<String> existingFileNameList = new ArrayList<String>();
					existingFileNameList = map.get(doc.getPages().getCount());
					existingFileNameList.add(updateFile.getName());
					map.put(pageCount, existingFileNameList);
				} else {
					List<String> existingFileNameList = new ArrayList<String>();
					existingFileNameList.add(updateFile.getName());
					map.put(pageCount, existingFileNameList);
				}

				BlobClient blobClient = containerClient.getBlobClient(str1[4].replace("%20", " "));
				BlobClient destblobclient = destcontainer.getBlobClient(str1[4].replace("%20", " "));
				destblobclient.beginCopy(blobClient.getBlobUrl(), null);
				cloudBlob.delete();
			}

			System.out.println("Map:" + map);

			map.forEach((key, value) -> {

				for (String temp : value) {
					if (temp.contains("Banner")) {
						String bannerPage = temp;
						value.remove(temp);
						value.add(0, bannerPage);
						break;
					}
				}

			});
			System.out.println("update Map:" + map);
			PDFMergerUtility PDFmerger = new PDFMergerUtility();
			map.forEach((key, fileNames) -> {
				for (String sortPdfFile : fileNames) {
					try {
						PDFmerger.addSource(fileRootLocation + "\\" + sortPdfFile);
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					}
				}
				try {
					String fileNoExt = fileNames.get(1).replaceFirst("[.][^.]+$", "");
					PDFmerger.setDestinationFileName(fileRootLocation + "\\" + fileNoExt + "-mgr" + ".pdf");
					PDFmerger.mergeDocuments();
					String outputFile = fileOutputLocation + fileNoExt + "-mgr" + ".pcl";
					doc.saveToFile(outputFile, FileFormat.PCL);
					File pdfMergePclFile = new File(outputFile);
					if (!pdfMergePclFile.exists()) {
						targetFile.mkdir();
					}
					FileInputStream input = new FileInputStream(pdfMergePclFile);
					client.blobName(pdfMergePclFile.getName()).buildClient().upload(input, pdfMergePclFile.length());
				} catch (Exception exception) {
					exception.printStackTrace();
				}
			});

			FileUtils.deleteDirectory(targetFile);
			
		} catch (Exception exception) {
			if (exception.getMessage().contains("BlobAlreadyExists"))
				result = "The specified document already exists";
			else
				result = "error in merge pdf and covert into pcl file successfully";
			System.out.println("Exception Message:" + exception);
		}
		//FileUtils.deleteDirectory(new File("src/main/resources/mergepcl/"));
		return result;
	}
}