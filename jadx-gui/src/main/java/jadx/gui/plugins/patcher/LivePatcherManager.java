package jadx.gui.plugins.patcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.gui.treemodel.JClass;
import jadx.gui.ui.MainWindow;

public class LivePatcherManager {
	private static final Logger LOG = LoggerFactory.getLogger(LivePatcherManager.class);

	private static final Path DEFAULT_BIN_DIR = Paths.get("tools", "apk-live-modder", "bin").toAbsolutePath();
	private static final Path FALLBACK_BIN_DIR = Paths.get(System.getProperty("user.home"), ".jadx-live-patcher", "bin");

	private final MainWindow mainWindow;
	private final Path binDir;

	public LivePatcherManager(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
		if (Files.exists(DEFAULT_BIN_DIR)) {
			this.binDir = DEFAULT_BIN_DIR;
		} else {
			this.binDir = FALLBACK_BIN_DIR;
		}
	}

	public Path getBinDir() {
		return binDir;
	}

	public void ensureTools(Consumer<String> progress) throws Exception {
		Files.createDirectories(binDir);
		downloadIfMissing("ecj-3.33.0.jar",
				"https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar", progress);
		downloadIfMissing("android.jar",
				"https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar", progress);
		downloadIfMissing("r8-8.2.42.jar",
				"https://maven.google.com/com/android/tools/r8/8.2.42/r8-8.2.42.jar", progress);
		downloadIfMissing("baksmali-2.5.2.jar",
				"https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar", progress);
		downloadIfMissing("apktool_2.10.0.jar",
				"https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar", progress);
		downloadIfMissing("uber-apk-signer-1.3.0.jar",
				"https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar", progress);
		progress.accept("All background tools verified.");
	}

	private void downloadIfMissing(String fileName, String downloadUrl, Consumer<String> progress) throws Exception {
		Path target = binDir.resolve(fileName);
		if (Files.exists(target) && Files.size(target) > 1000) {
			return;
		}
		progress.accept("Downloading " + fileName + "...");
		Path tmp = binDir.resolve(fileName + ".tmp");
		HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
		conn.setRequestProperty("User-Agent", "Mozilla/5.0");
		conn.setConnectTimeout(15000);
		conn.setReadTimeout(30000);

		try (InputStream in = conn.getInputStream();
				FileOutputStream out = new FileOutputStream(tmp.toFile())) {
			byte[] buf = new byte[65536];
			int read;
			while ((read = in.read(buf)) != -1) {
				out.write(buf, 0, read);
			}
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		progress.accept("Downloaded: " + fileName);
	}

	public Path prepareApktoolWorkspace(Path apkPath, Consumer<String> progress) throws Exception {
		String baseName = apkPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
		Path workspace = binDir.getParent().resolve("workspace").resolve(baseName);
		Path apktoolDir = workspace.resolve("apktool_src");

		if (!Files.exists(apktoolDir.resolve("AndroidManifest.xml"))) {
			progress.accept("Disassembling APK with Apktool into workspace...");
			Files.createDirectories(workspace);
			Path apktoolJar = binDir.resolve("apktool_2.10.0.jar");
			runCmd(progress, "java", "-jar", apktoolJar.toString(), "d", "-f", "-o", apktoolDir.toString(), apkPath.toString());
			progress.accept("Apktool disassembly complete.");
		} else {
			progress.accept("Using existing Apktool workspace: " + apktoolDir);
		}
		return workspace;
	}

	public Path patchClassAndRebuild(Path apkPath, JClass jClass, String newJavaCode, Consumer<String> progress) throws Exception {
		ensureTools(progress);
		Path workspace = prepareApktoolWorkspace(apkPath, progress);
		Path apktoolDir = workspace.resolve("apktool_src");
		Path buildDir = workspace.resolve("build");
		Path distDir = workspace.resolve("dist");

		FileUtils.deleteQuietly(buildDir.toFile());
		Files.createDirectories(buildDir);
		Files.createDirectories(distDir);

		Path classesDir = buildDir.resolve("classes");
		Path dexDir = buildDir.resolve("dex");
		Path smaliDir = buildDir.resolve("smali");
		Files.createDirectories(classesDir);
		Files.createDirectories(dexDir);
		Files.createDirectories(smaliDir);

		// 1. Write modified Java file to disk
		String rawClsName = jClass.getFullName();
		String relPath = rawClsName.replace('.', File.separatorChar) + ".java";
		Path tempJavaFile = buildDir.resolve("temp_src").resolve(relPath);
		Files.createDirectories(tempJavaFile.getParent());
		Files.writeString(tempJavaFile, newJavaCode, StandardCharsets.UTF_8);

		progress.accept("Step 1/5: Compiling modified Java with ECJ...");
		Path ecjJar = binDir.resolve("ecj-3.33.0.jar");
		Path androidJar = binDir.resolve("android.jar");
		String classpath = androidJar + File.pathSeparator + apkPath.toAbsolutePath();

		runCmd(progress, "java", "-jar", ecjJar.toString(),
				"-1.8", "-nowarn", "-proceedOnError",
				"-cp", classpath,
				"-d", classesDir.toString(),
				tempJavaFile.toString());

		List<Path> classFiles = new ArrayList<>();
		try (Stream<Path> stream = Files.walk(classesDir)) {
			stream.filter(p -> p.toString().endsWith(".class")).forEach(classFiles::add);
		}
		if (classFiles.isEmpty()) {
			throw new RuntimeException("ECJ failed to produce any .class files for " + tempJavaFile);
		}
		progress.accept("Generated " + classFiles.size() + " class file(s).");

		// 2. Convert to DEX with D8
		progress.accept("Step 2/5: Converting bytecode to DEX with D8...");
		Path r8Jar = binDir.resolve("r8-8.2.42.jar");
		List<String> d8Cmd = new ArrayList<>();
		d8Cmd.add("java");
		d8Cmd.add("-cp");
		d8Cmd.add(r8Jar.toString());
		d8Cmd.add("com.android.tools.r8.D8");
		d8Cmd.add("--min-api");
		d8Cmd.add("21");
		d8Cmd.add("--intermediate");
		d8Cmd.add("--lib");
		d8Cmd.add(androidJar.toString());
		d8Cmd.add("--output");
		d8Cmd.add(dexDir.toString());
		for (Path cf : classFiles) {
			d8Cmd.add(cf.toString());
		}
		runCmd(progress, d8Cmd.toArray(new String[0]));

		Path dexFile = dexDir.resolve("classes.dex");
		if (!Files.exists(dexFile)) {
			throw new RuntimeException("D8 failed to generate classes.dex");
		}

		// 3. Disassemble to Smali with Baksmali
		progress.accept("Step 3/5: Disassembling DEX to Smali with Baksmali...");
		Path baksmaliJar = binDir.resolve("baksmali-2.5.2.jar");
		runCmd(progress, "java", "-jar", baksmaliJar.toString(), "d", dexFile.toString(), "-o", smaliDir.toString());

		List<Path> smaliFiles = new ArrayList<>();
		try (Stream<Path> stream = Files.walk(smaliDir)) {
			stream.filter(p -> p.toString().endsWith(".smali")).forEach(smaliFiles::add);
		}
		if (smaliFiles.isEmpty()) {
			throw new RuntimeException("Baksmali failed to generate .smali files");
		}

		// 4. Hot-swap into Apktool directory
		progress.accept("Step 4/5: Hot-swapping " + smaliFiles.size() + " Smali file(s) into Apktool workspace...");
		for (Path generatedSmali : smaliFiles) {
			Path relSmali = smaliDir.relativize(generatedSmali);
			boolean targetFound = false;

			List<Path> smaliRoots;
			try (Stream<Path> roots = Files.list(apktoolDir)) {
				smaliRoots = roots
						.filter(Files::isDirectory)
						.filter(p -> p.getFileName().toString().startsWith("smali"))
						.collect(java.util.stream.Collectors.toList());
			}
			for (Path root : smaliRoots) {
				Path target = root.resolve(relSmali);
				if (Files.exists(target) || Files.exists(target.getParent())) {
					Files.createDirectories(target.getParent());
					Files.copy(generatedSmali, target, StandardCopyOption.REPLACE_EXISTING);
					progress.accept("Patched: " + root.getFileName() + "/" + relSmali);
					targetFound = true;
					break;
				}
			}
			if (!targetFound) {
				Path defaultTarget = apktoolDir.resolve("smali").resolve(relSmali);
				Files.createDirectories(defaultTarget.getParent());
				Files.copy(generatedSmali, defaultTarget, StandardCopyOption.REPLACE_EXISTING);
				progress.accept("Placed in primary smali: " + relSmali);
			}
		}

		// 5. Rebuild and Sign APK
		progress.accept("Step 5/5: Reassembling and Signing APK with Apktool & Uber-Signer...");
		Path apktoolJar = binDir.resolve("apktool_2.10.0.jar");
		Path unsignedApk = buildDir.resolve("unsigned.apk");
		runCmd(progress, "java", "-jar", apktoolJar.toString(), "b", "-f", apktoolDir.toString(), "-o", unsignedApk.toString());

		Path signerJar = binDir.resolve("uber-apk-signer-1.3.0.jar");
		runCmd(progress, "java", "-jar", signerJar.toString(), "--apks", unsignedApk.toString(), "--out", distDir.toString(),
				"--overwrite");

		Path finalApk = null;
		try (Stream<Path> stream = Files.list(distDir)) {
			finalApk = stream
					.filter(p -> p.toString().endsWith(".apk"))
					.findFirst()
					.orElse(null);
		}
		if (finalApk == null) {
			throw new RuntimeException("No signed APK produced in " + distDir);
		}

		progress.accept("🎉 BUILD SUCCESS! Final APK ready at:\n" + finalApk.toAbsolutePath());
		return finalApk;
	}

	private void runCmd(Consumer<String> progress, String... cmd) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				LOG.debug("LivePatcher: {}", line);
				if (line.contains("error") || line.contains("Warning") || line.contains("Built") || line.contains("Signed")) {
					progress.accept(line);
				}
			}
		}
		int exit = p.waitFor();
		if (exit != 0) {
			LOG.warn("LivePatcher command exited with code {}: {}", exit, String.join(" ", cmd));
		}
	}
}
