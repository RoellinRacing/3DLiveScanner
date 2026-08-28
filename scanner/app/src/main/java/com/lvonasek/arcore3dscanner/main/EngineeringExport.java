package com.lvonasek.arcore3dscanner.main;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.lvonasek.arcore3dscanner.BuildConfig;
import com.lvonasek.arcore3dscanner.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds model artifacts in app cache and exposes SAF/share intents for them. */
public final class EngineeringExport {
  public enum Format {
    STL,
    THREE_MF,
    PLY
  }

  public static final class Artifact {
    public final File file;
    public final String displayName;
    public final String mimeType;

    private Artifact(File file, String displayName, String mimeType) {
      this.file = file;
      this.displayName = displayName;
      this.mimeType = mimeType;
    }
  }

  private static final int BUFFER_SIZE = 64 * 1024;

  private EngineeringExport() {
  }

  /** Exports the model currently loaded in the native editor scene. */
  public static Artifact exportCurrentModel(Context context, String requestedName, Format format)
      throws IOException {
    String base = safeBaseName(requestedName);
    File directory = exportCache(context);

    if (format == Format.THREE_MF) {
      File xml = new File(directory, base + ".model");
      File output = new File(directory, base + ".3mf");
      deleteIfExists(xml);
      deleteIfExists(output);
      if (!JNI.exportEngineering(
          xml.getAbsolutePath().getBytes(StandardCharsets.UTF_8), 1)) {
        throw new IOException("Native 3MF geometry export failed");
      }
      try {
        package3mf(xml, output);
      } finally {
        deleteQuietly(xml);
      }
      return new Artifact(output, output.getName(), "model/3mf");
    }

    final int nativeFormat;
    final File output;
    final String mimeType;
    if (format == Format.STL) {
      nativeFormat = 0;
      output = new File(directory, base + ".stl");
      mimeType = "model/stl";
    } else {
      nativeFormat = 2;
      output = new File(directory, base + ".ply");
      mimeType = "application/octet-stream";
    }

    deleteIfExists(output);
    if (!JNI.exportEngineering(
        output.getAbsolutePath().getBytes(StandardCharsets.UTF_8), nativeFormat)) {
      throw new IOException("Native model export failed");
    }
    return new Artifact(output, output.getName(), mimeType);
  }

  /** Exports the current in-memory mesh and textures, then creates a portable OBJ package. */
  public static Artifact exportCurrentObj(Context context, String requestedName)
      throws IOException {
    return exportCurrentObj(context, requestedName, null);
  }

  /** As above, optionally preserving position.txt from the loaded model directory. */
  public static Artifact exportCurrentObj(Context context, String requestedName,
                                          File loadedModel)
      throws IOException {
    String base = safeBaseName(requestedName);
    File source = new File(exportCache(context), base + "-obj-source");
    deleteTreeQuietly(source);
    if (!source.mkdirs()) throw new IOException("Cannot create OBJ export directory");

    File obj = new File(source, base + ".obj");
    try {
      JNI.saveWithTextures(obj.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
      if (!obj.isFile() || obj.length() == 0) {
        throw new IOException("Native textured OBJ export failed");
      }
      File metadataRoot = loadedModel != null && loadedModel.isDirectory()
          ? loadedModel : loadedModel == null ? null : loadedModel.getParentFile();
      File position = metadataRoot == null ? null : new File(metadataRoot, "position.txt");
      if (position != null && position.isFile()) {
        try (InputStream input = new BufferedInputStream(new FileInputStream(position));
             OutputStream output = new BufferedOutputStream(
                 new FileOutputStream(new File(source, "position.txt")))) {
          copy(input, output);
        }
      }
      return packageObj(context, source, requestedName);
    } finally {
      deleteTreeQuietly(source);
    }
  }

  /** Wraps an already-existing file for SAF or Android sharing. */
  public static Artifact existingFile(File file, String mimeType) throws IOException {
    if (file == null || !file.isFile() || !file.canRead()) {
      throw new IOException("Export file is not readable");
    }
    return new Artifact(file, file.getName(), mimeType);
  }

  /** Creates a textured OBJ package containing only the OBJ and its referenced resources. */
  public static Artifact packageObj(Context context, File modelFileOrDirectory,
                                    String requestedName)
      throws IOException {
    File obj = findObj(modelFileOrDirectory);
    if (obj == null) {
      throw new IOException("OBJ model file is not readable");
    }

    File root = obj.getParentFile();
    List<File> files = new ArrayList<>();
    files.add(obj);
    for (String resource : Exporter.getObjResources(obj)) {
      File file = checkedChild(root, resource);
      if (file.isFile() && !files.contains(file)) files.add(file);
    }
    File position = new File(root, "position.txt");
    if (position.isFile()) files.add(position);
    Collections.sort(files,
        (first, second) -> relative(root, first).compareTo(relative(root, second)));

    File output = new File(exportCache(context), safeBaseName(requestedName) + "-obj.zip");
    deleteIfExists(output);
    zipFiles(root, files, output);
    return new Artifact(output, output.getName(), "application/zip");
  }

  /** Creates a versioned raw package for later reconstruction and diagnostics. */
  public static Artifact packageRawScan(Context context, File datasetDirectory,
                                        String requestedName) throws Exception {
    if (datasetDirectory == null || !datasetDirectory.isDirectory()) {
      throw new IOException("Scan dataset directory is not readable");
    }

    File output = new File(exportCache(context), safeBaseName(requestedName) + ".scanpkg");
    deleteIfExists(output);

    List<File> files = sortedFiles(datasetDirectory);
    JSONObject manifest = new JSONObject();
    manifest.put("format", "com.3dlivescanner.scanpkg");
    manifest.put("schemaVersion", 1);
    manifest.put("linearUnit", "metre");
    manifest.put("coordinateSystem", "native-ar-world");
    manifest.put("payloadRoot", "data/");

    SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    date.setTimeZone(TimeZone.getTimeZone("UTC"));
    manifest.put("createdUtc", date.format(new Date()));

    JSONObject frameLayout = new JSONObject();
    frameLayout.put("image", "%08d.jpg");
    frameLayout.put("poses", "%08d.mat");
    frameLayout.put("pointCloud", "%08d.pcl");
    frameLayout.put("previewMesh", "%08d.bin");
    manifest.put("frameLayout", frameLayout);

    JSONArray entries = new JSONArray();
    for (File file : files) {
      JSONObject entry = new JSONObject();
      entry.put("path", relative(datasetDirectory, file));
      entry.put("bytes", file.length());
      entry.put("sha256", sha256(file));
      entries.put(entry);
    }
    manifest.put("files", entries);

    try (ZipOutputStream zip = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(output)))) {
      zip.setLevel(Deflater.BEST_SPEED);
      putBytes(zip, "manifest.json", manifest.toString(2).getBytes(StandardCharsets.UTF_8));
      for (File file : files) {
        putFile(zip, "data/" + relative(datasetDirectory, file), file);
      }
    } catch (Exception error) {
      deleteQuietly(output);
      throw error;
    }
    return new Artifact(output, output.getName(), "application/zip");
  }

  /** Opens Android's document picker. Google Drive is offered through its DocumentsProvider. */
  public static Intent createDocumentIntent(Artifact artifact) {
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(artifact.mimeType);
    intent.putExtra(Intent.EXTRA_TITLE, artifact.displayName);
    return intent;
  }

  /** Streams a prepared artifact to the content URI returned by ACTION_CREATE_DOCUMENT. */
  public static void writeToDocument(Context context, Artifact artifact, Uri destination)
      throws IOException {
    try (InputStream input = new BufferedInputStream(new FileInputStream(artifact.file));
         OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
      if (output == null) {
        throw new IOException("Document provider returned no output stream");
      }
      copy(input, output);
      output.flush();
    }
  }

  /** Creates an ACTION_SEND chooser for Drive, mail, messengers and other targets. */
  public static Intent createShareIntent(Context context, Artifact artifact) {
    Uri uri = FileProvider.getUriForFile(
        context, BuildConfig.APPLICATION_ID + ".provider", artifact.file);
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType(artifact.mimeType);
    intent.putExtra(Intent.EXTRA_STREAM, uri);
    intent.setClipData(ClipData.newRawUri(artifact.displayName, uri));
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    return Intent.createChooser(intent, context.getString(R.string.share_via));
  }

  private static void package3mf(File modelXml, File output) throws IOException {
    final String contentTypes =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=" +
        "\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"model\" ContentType=" +
        "\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>" +
        "</Types>";
    final String relationships =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=" +
        "\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>" +
        "</Relationships>";

    try (ZipOutputStream zip = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(output)))) {
      zip.setLevel(Deflater.BEST_COMPRESSION);
      putBytes(zip, "[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
      putBytes(zip, "_rels/.rels", relationships.getBytes(StandardCharsets.UTF_8));
      putFile(zip, "3D/3dmodel.model", modelXml);
    } catch (IOException error) {
      deleteQuietly(output);
      throw error;
    }
  }

  private static void zipFiles(File base, List<File> files, File output) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(output)))) {
      zip.setLevel(Deflater.BEST_SPEED);
      for (File file : files) putFile(zip, relative(base, file), file);
    } catch (IOException error) {
      deleteQuietly(output);
      throw error;
    }
  }

  private static File findObj(File fileOrDirectory) throws IOException {
    if (fileOrDirectory == null) return null;
    if (fileOrDirectory.isFile()) {
      return fileOrDirectory.getName().toLowerCase(Locale.US).endsWith(Exporter.EXT_OBJ)
          ? fileOrDirectory.getCanonicalFile()
          : null;
    }
    if (!fileOrDirectory.isDirectory()) return null;

    File[] children = fileOrDirectory.listFiles();
    if (children == null) return null;
    List<File> candidates = new ArrayList<>();
    for (File child : children) {
      if (child.isFile() &&
          child.getName().toLowerCase(Locale.US).endsWith(Exporter.EXT_OBJ)) {
        candidates.add(child);
      }
    }
    Collections.sort(candidates, (first, second) -> first.getName().compareTo(second.getName()));
    return candidates.isEmpty() ? null : candidates.get(0).getCanonicalFile();
  }

  private static File checkedChild(File root, String relativePath) throws IOException {
    File child = new File(root, relativePath).getCanonicalFile();
    String rootPath = root.getCanonicalPath() + File.separator;
    if (!child.getPath().startsWith(rootPath)) {
      throw new IOException("OBJ resource escaped model directory");
    }
    return child;
  }

  private static List<File> sortedFiles(File base) throws IOException {
    List<File> files = new ArrayList<>();
    collectFiles(base, base, files);
    Collections.sort(files,
        (first, second) -> relative(base, first).compareTo(relative(base, second)));
    return files;
  }

  private static void collectFiles(File base, File current, List<File> output)
      throws IOException {
    String basePath = base.getCanonicalPath();
    String root = basePath + File.separator;
    String currentPath = current.getCanonicalPath();
    if (!currentPath.equals(basePath) && !currentPath.startsWith(root)) {
      throw new IOException("Path escaped package root");
    }
    if (current.isFile()) {
      output.add(current);
      return;
    }

    File[] children = current.listFiles();
    if (children == null) throw new IOException("Cannot list " + current);
    for (File child : children) collectFiles(base, child, output);
  }

  private static String relative(File base, File file) {
    return base.toURI().relativize(file.toURI()).getPath();
  }

  private static void putBytes(ZipOutputStream zip, String name, byte[] data)
      throws IOException {
    ZipEntry entry = new ZipEntry(name);
    zip.putNextEntry(entry);
    zip.write(data);
    zip.closeEntry();
  }

  private static void putFile(ZipOutputStream zip, String name, File file)
      throws IOException {
    ZipEntry entry = new ZipEntry(name.replace(File.separatorChar, '/'));
    zip.putNextEntry(entry);
    try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
      copy(input, zip);
    }
    zip.closeEntry();
  }

  private static void copy(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    int count;
    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
  }

  private static String sha256(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
      byte[] buffer = new byte[BUFFER_SIZE];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }

    StringBuilder result = new StringBuilder(64);
    for (byte value : digest.digest()) {
      result.append(String.format(Locale.US, "%02x", value & 0xff));
    }
    return result.toString();
  }

  private static File exportCache(Context context) throws IOException {
    File directory = new File(context.getCacheDir(), "exports");
    if (!directory.exists() && !directory.mkdirs()) {
      throw new IOException("Cannot create export cache");
    }
    return directory;
  }

  private static void deleteIfExists(File file) throws IOException {
    if (file.exists() && !file.delete()) throw new IOException("Cannot replace " + file);
  }

  private static void deleteQuietly(File file) {
    if (file.exists()) file.delete();
  }

  private static void deleteTreeQuietly(File file) {
    if (file == null || !file.exists()) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) deleteTreeQuietly(child);
      }
    }
    file.delete();
  }

  private static String safeBaseName(String input) {
    String base = input == null ? "scan" : input.trim();
    base = base.replaceFirst("(?i)\\.(obj|ply|stl|3mf|scanpkg|zip)$", "");
    base = base.replaceAll("[^A-Za-z0-9._-]", "_");
    return base.isEmpty() ? "scan" : base;
  }
}
