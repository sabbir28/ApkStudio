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
import java.util.Optional;
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

	@FunctionalInterface
	public interface ProgressListener {
		void onProgress(int percent, String message);
	}

	public Path patchClassAndRebuild(Path apkPath, JClass jClass, String newJavaCode, Consumer<String> progress) throws Exception {
		return patchClassAndRebuild(apkPath, jClass, newJavaCode, (pct, msg) -> progress.accept(msg));
	}

	public Path patchClassAndRebuild(Path apkPath, JClass jClass, String newJavaCode, ProgressListener progress) throws Exception {
		progress.onProgress(5, "Verifying build tools & environment...");
		ensureTools(msg -> progress.onProgress(8, msg));
		progress.onProgress(12, "Preparing Apktool workspace...");
		Path workspace = prepareApktoolWorkspace(apkPath, msg -> progress.onProgress(18, msg));
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

		progress.onProgress(25, "Step 1/5: Compiling modified Java with ECJ...");
		Path ecjJar = binDir.resolve("ecj-3.33.0.jar");
		Path androidJar = binDir.resolve("android.jar");
		String classpath = androidJar + File.pathSeparator + apkPath.toAbsolutePath();

		runCmd(msg -> progress.onProgress(35, msg), "java", "-jar", ecjJar.toString(),
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
		progress.onProgress(45, "Generated " + classFiles.size() + " class file(s).");

		// 2. Convert to DEX with D8
		progress.onProgress(50, "Step 2/5: Converting bytecode to DEX with D8...");
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
		runCmd(msg -> progress.onProgress(55, msg), d8Cmd.toArray(new String[0]));

		Path dexFile = dexDir.resolve("classes.dex");
		if (!Files.exists(dexFile)) {
			throw new RuntimeException("D8 failed to generate classes.dex");
		}
		progress.onProgress(60, "classes.dex generated successfully.");

		// 3. Disassemble to Smali with Baksmali
		progress.onProgress(65, "Step 3/5: Disassembling DEX to Smali with Baksmali...");
		Path baksmaliJar = binDir.resolve("baksmali-2.5.2.jar");
		runCmd(msg -> progress.onProgress(70, msg), "java", "-jar", baksmaliJar.toString(), "d", dexFile.toString(), "-o",
				smaliDir.toString());

		List<Path> smaliFiles = new ArrayList<>();
		try (Stream<Path> stream = Files.walk(smaliDir)) {
			stream.filter(p -> p.toString().endsWith(".smali")).forEach(smaliFiles::add);
		}
		if (smaliFiles.isEmpty()) {
			throw new RuntimeException("Baksmali failed to generate .smali files");
		}
		progress.onProgress(75, "Generated " + smaliFiles.size() + " smali file(s).");

		// 4. Hot-swap into Apktool directory
		progress.onProgress(78, "Step 4/5: Hot-swapping " + smaliFiles.size() + " Smali file(s) into Apktool workspace...");
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
					progress.onProgress(82, "Patched: " + root.getFileName() + "/" + relSmali);
					targetFound = true;
					break;
				}
			}
			if (!targetFound) {
				Path defaultTarget = apktoolDir.resolve("smali").resolve(relSmali);
				Files.createDirectories(defaultTarget.getParent());
				Files.copy(generatedSmali, defaultTarget, StandardCopyOption.REPLACE_EXISTING);
				progress.onProgress(82, "Placed in primary smali: " + relSmali);
			}
		}

		// 5. Rebuild and Sign APK
		return rebuildAndSignApk(apktoolDir, buildDir, distDir, progress);
	}

	public Path patchResourceAndRebuild(Path apkPath, String relResPath, String newContent, Consumer<String> progress)
			throws Exception {
		return patchResourceAndRebuild(apkPath, relResPath, newContent, (pct, msg) -> progress.accept(msg));
	}

	public Path patchResourceAndRebuild(Path apkPath, String relResPath, String newContent, ProgressListener progress)
			throws Exception {
		progress.onProgress(10, "Verifying build tools & environment...");
		ensureTools(msg -> progress.onProgress(15, msg));
		progress.onProgress(25, "Preparing Apktool workspace...");
		Path workspace = prepareApktoolWorkspace(apkPath, msg -> progress.onProgress(35, msg));
		Path apktoolDir = workspace.resolve("apktool_src");
		Path buildDir = workspace.resolve("build");
		Path distDir = workspace.resolve("dist");

		Files.createDirectories(buildDir);
		Files.createDirectories(distDir);

		progress.onProgress(50, "Step 1/2: Updating decoded resource in Apktool workspace...");
		Path targetFile = resolveApktoolResource(apktoolDir, relResPath);
		Files.createDirectories(targetFile.getParent());
		Files.writeString(targetFile, newContent, StandardCharsets.UTF_8);
		progress.onProgress(65, "Saved modified resource: " + apktoolDir.relativize(targetFile));

		return rebuildAndSignApk(apktoolDir, buildDir, distDir, progress);
	}

	public String loadDecodedResource(Path apkPath, String relResPath, Consumer<String> progress) throws Exception {
		ensureTools(progress);
		Path workspace = prepareApktoolWorkspace(apkPath, progress);
		Path apktoolDir = workspace.resolve("apktool_src");
		Path targetFile = resolveApktoolResource(apktoolDir, relResPath);
		if (Files.exists(targetFile)) {
			return Files.readString(targetFile, StandardCharsets.UTF_8);
		}
		return null;
	}

	public Path resolveApktoolResource(Path apktoolDir, String relResPath) {
		String clean = relResPath.replace('\\', '/').trim();
		while (clean.startsWith("/")) {
			clean = clean.substring(1);
		}
		if (clean.endsWith("AndroidManifest.xml") || clean.equalsIgnoreCase("AndroidManifest.xml")) {
			return apktoolDir.resolve("AndroidManifest.xml");
		}
		if (clean.startsWith("resources/")) {
			clean = clean.substring("resources/".length());
		}
		Path direct = apktoolDir.resolve(clean);
		if (Files.exists(direct)) {
			return direct;
		}
		Path inRes = apktoolDir.resolve("res").resolve(clean);
		if (Files.exists(inRes)) {
			return inRes;
		}
		String fileName = Paths.get(clean).getFileName().toString();
		final String matchClean = clean;
		final String matchFileName = fileName;
		try (Stream<Path> stream = Files.walk(apktoolDir)) {
			Optional<Path> found = stream
					.filter(Files::isRegularFile)
					.filter(p -> p.toString().replace('\\', '/').endsWith(matchClean)
							|| p.getFileName().toString().equals(matchFileName))
					.findFirst();
			if (found.isPresent()) {
				return found.get();
			}
		} catch (Exception ignored) {
		}
		return direct;
	}

	private Path rebuildAndSignApk(Path apktoolDir, Path buildDir, Path distDir, ProgressListener progress) throws Exception {
		Path origMetaInf = apktoolDir.resolve("original").resolve("META-INF");
		if (Files.exists(origMetaInf)) {
			FileUtils.deleteQuietly(origMetaInf.toFile());
		}

		progress.onProgress(85, "Reassembling APK with Apktool (compiling XML resources & smali)...");
		Path apktoolJar = binDir.resolve("apktool_2.10.0.jar");
		Path unsignedApk = buildDir.resolve("unsigned.apk");
		runCmd(msg -> progress.onProgress(88, msg), "java", "-jar", apktoolJar.toString(), "b", "-f", apktoolDir.toString(), "-o",
				unsignedApk.toString());

		if (!Files.exists(unsignedApk)) {
			throw new RuntimeException("Apktool failed to build " + unsignedApk);
		}

		progress.onProgress(92, "Signing and aligning APK with Uber-APK-Signer...");
		Path signerJar = binDir.resolve("uber-apk-signer-1.3.0.jar");
		runCmd(msg -> progress.onProgress(95, msg), "java", "-jar", signerJar.toString(),
				"--apks", unsignedApk.toString(),
				"--out", distDir.toString(),
				"--allowResign",
				"--overwrite");

		Path finalApk = null;
		try (Stream<Path> stream = Files.walk(distDir)) {
			finalApk = stream
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".apk"))
					.sorted((a, b) -> {
						boolean aSigned = a.getFileName().toString().contains("debugSigned");
						boolean bSigned = b.getFileName().toString().contains("debugSigned");
						return Boolean.compare(bSigned, aSigned);
					})
					.findFirst()
					.orElse(null);
		}
		if (finalApk == null) {
			Path fallbackApk = distDir.resolve("modified-unsigned.apk");
			Files.copy(unsignedApk, fallbackApk, StandardCopyOption.REPLACE_EXISTING);
			finalApk = fallbackApk;
			progress.onProgress(98, "Warning: Uber-Signer did not output signed apk, fallback to: " + fallbackApk.getFileName());
		}

		progress.onProgress(100, "BUILD SUCCESS! Final APK ready at:\n" + finalApk.toAbsolutePath());
		return finalApk;
	}

	private void runCmd(Consumer<String> progress, String... cmd) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					LOG.debug("LivePatcher: {}", trimmed);
					progress.accept(trimmed);
				}
			}
		}
		int exit = p.waitFor();
		if (exit != 0) {
			LOG.warn("LivePatcher command exited with code {}: {}", exit, String.join(" ", cmd));
		}
	}
}
