package jadx.gui.plugins.patcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.TitledBorder;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.ResourceFile;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.gui.treemodel.JClass;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JResource;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.panel.ContentPanel;
import jadx.gui.utils.UiUtils;
import jadx.gui.utils.ui.NodeLabel;

public class LivePatchDialog extends JDialog {
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(LivePatchDialog.class);

	private final MainWindow mainWindow;
	private final LivePatcherManager patcherManager;
	private final List<Path> apkFiles;

	private JComboBox<Path> apkCombo;
	private JLabel targetTypeLabel;
	private JLabel targetLabel;
	private RSyntaxTextArea codeArea;
	private TitledBorder codeBorder;
	private JLabel statusDetailLabel;
	private JLabel stepBadge;
	private JProgressBar progressBar;
	private JTextArea logArea;
	private JButton btnRebuild;
	private JButton btnOpenDist;

	private JClass targetClass;
	private JResource targetResource;
	private Path outputApk;

	public static void open(MainWindow mainWindow) {
		ContentPanel activeTab = mainWindow.getTabbedPane().getSelectedContentPanel();
		if (activeTab != null) {
			JNode node = activeTab.getNode();
			if (node instanceof JClass) {
				openForClass(mainWindow, (JClass) node);
				return;
			}
			if (node instanceof JResource) {
				openForResource(mainWindow, (JResource) node);
				return;
			}
		}
		new LivePatchDialog(mainWindow).setVisible(true);
	}

	public static void openForClass(MainWindow mainWindow, JClass jClass) {
		new LivePatchDialog(mainWindow, jClass).setVisible(true);
	}

	public static void openForResource(MainWindow mainWindow, JResource jResource) {
		new LivePatchDialog(mainWindow, jResource).setVisible(true);
	}

	public static void openForManifest(MainWindow mainWindow) {
		LivePatchDialog dialog = new LivePatchDialog(mainWindow);
		if (dialog.loadManifestResource()) {
			dialog.setVisible(true);
		} else {
			JOptionPane.showMessageDialog(mainWindow,
					"AndroidManifest.xml was not found in the current project.",
					"Not Found", JOptionPane.WARNING_MESSAGE);
		}
	}

	public LivePatchDialog(MainWindow mainWindow) {
		this(mainWindow, (JClass) null);
	}

	public LivePatchDialog(MainWindow mainWindow, JClass initialClass) {
		super(mainWindow);
		this.mainWindow = mainWindow;
		this.targetClass = initialClass;
		this.targetResource = null;
		this.patcherManager = new LivePatcherManager(mainWindow);
		this.apkFiles = filterApkFiles(mainWindow);

		initUI();
		if (initialClass != null) {
			setTargetClass(initialClass);
		} else {
			loadManifestResource();
		}
	}

	public LivePatchDialog(MainWindow mainWindow, JResource initialResource) {
		super(mainWindow);
		this.mainWindow = mainWindow;
		this.targetClass = null;
		this.targetResource = initialResource;
		this.patcherManager = new LivePatcherManager(mainWindow);
		this.apkFiles = filterApkFiles(mainWindow);

		initUI();
		if (initialResource != null) {
			setTargetResource(initialResource);
		}
	}

	private List<Path> filterApkFiles(MainWindow mainWindow) {
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**.{apk,xapk,apks,apkm}");
		return mainWindow.getProject().getFilePaths().stream()
				.filter(matcher::matches)
				.collect(Collectors.toList());
	}

	private void initUI() {
		setTitle("Live APK Patcher & Builder - Java & Resource Editor");
		setLayout(new BorderLayout(5, 5));

		// Top panel: APK selector, Target info, and quick buttons
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

		JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		row1.add(new JLabel("Target APK:"));
		apkCombo = new JComboBox<>(apkFiles.toArray(new Path[0]));
		apkCombo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
			String name = value != null ? value.getFileName().toString() : "None";
			return new NodeLabel(name);
		});
		row1.add(apkCombo);

		row1.add(Box.createHorizontalStrut(15));
		targetTypeLabel = new JLabel("Target:");
		row1.add(targetTypeLabel);

		targetLabel = new JLabel("None selected");
		targetLabel.setFont(new Font("Consolas", Font.BOLD, 12));
		targetLabel.setForeground(new Color(0x38, 0x8e, 0x3c));
		row1.add(targetLabel);

		row1.add(Box.createHorizontalStrut(15));
		JButton btnManifest = new JButton("Edit AndroidManifest.xml");
		btnManifest.addActionListener(e -> loadManifestResource());
		row1.add(btnManifest);

		topPanel.add(row1);
		add(topPanel, BorderLayout.NORTH);

		// Center: RSyntaxTextArea
		codeArea = new RSyntaxTextArea(25, 80);
		codeArea.setCodeFoldingEnabled(true);
		codeArea.setAntiAliasingEnabled(true);
		codeArea.setFont(new Font("Consolas", Font.PLAIN, 13));

		RTextScrollPane codeScrollPane = new RTextScrollPane(codeArea);
		codeBorder = BorderFactory.createTitledBorder("Editable Code");
		codeScrollPane.setBorder(codeBorder);
		add(codeScrollPane, BorderLayout.CENTER);

		// Bottom panel: Progress bar, Diagnostics, and Action buttons
		JPanel bottomPanel = new JPanel(new BorderLayout(5, 6));
		bottomPanel.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));

		JPanel centerBottom = new JPanel(new BorderLayout(0, 6));

		// Visible Progress Bar & Status Section
		JPanel progressPanel = new JPanel(new BorderLayout(0, 4));
		progressPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));

		JPanel statusHeader = new JPanel(new BorderLayout(5, 0));
		statusHeader.setOpaque(false);

		statusDetailLabel = new JLabel("Status: Ready to patch & rebuild");
		statusDetailLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
		statusDetailLabel.setForeground(new Color(0x33, 0x33, 0x33));

		stepBadge = new JLabel("Idle");
		stepBadge.setFont(new Font("Segoe UI", Font.BOLD, 11));
		stepBadge.setForeground(new Color(0x75, 0x75, 0x75));

		statusHeader.add(statusDetailLabel, BorderLayout.WEST);
		statusHeader.add(stepBadge, BorderLayout.EAST);
		progressPanel.add(statusHeader, BorderLayout.NORTH);

		progressBar = new JProgressBar(0, 100);
		progressBar.setValue(0);
		progressBar.setStringPainted(true);
		progressBar.setString("0% - Ready");
		progressBar.setPreferredSize(new Dimension(800, 24));
		progressBar.setFont(new Font("Segoe UI", Font.BOLD, 11));
		progressBar.setForeground(new Color(0x19, 0x76, 0xd2));
		progressPanel.add(progressBar, BorderLayout.CENTER);

		centerBottom.add(progressPanel, BorderLayout.NORTH);

		logArea = new JTextArea(5, 80);
		logArea.setEditable(false);
		logArea.setFont(new Font("Consolas", Font.PLAIN, 11));
		logArea.setBackground(new Color(0x1e, 0x1e, 0x1e));
		logArea.setForeground(new Color(0xd4, 0xd4, 0xd4));
		JScrollPane logScrollPane = new JScrollPane(logArea);
		logScrollPane.setBorder(BorderFactory.createTitledBorder("Build Diagnostics & Progress"));
		centerBottom.add(logScrollPane, BorderLayout.CENTER);

		bottomPanel.add(centerBottom, BorderLayout.CENTER);

		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 5));
		btnRebuild = new JButton("Compile & Rebuild APK");
		btnRebuild.setFont(new Font("Segoe UI", Font.BOLD, 12));
		btnRebuild.addActionListener(e -> startPatchAndRebuild());

		btnOpenDist = new JButton("Open Output Folder");
		btnOpenDist.setEnabled(false);
		btnOpenDist.addActionListener(e -> openOutputFolder());

		JButton btnClose = new JButton("Close");
		btnClose.addActionListener(e -> dispose());

		buttonRow.add(btnRebuild);
		buttonRow.add(btnOpenDist);
		buttonRow.add(btnClose);
		bottomPanel.add(buttonRow, BorderLayout.SOUTH);

		add(bottomPanel, BorderLayout.SOUTH);

		setSize(960, 720);
		setLocationRelativeTo(mainWindow);
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		UiUtils.addEscapeShortCutToDispose(this);
	}

	public void setTargetClass(JClass cls) {
		this.targetClass = cls;
		this.targetResource = null;
		targetTypeLabel.setText("Class:");
		targetLabel.setText(cls.getFullName());
		targetLabel.setForeground(new Color(0x38, 0x8e, 0x3c));

		codeArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
		codeBorder.setTitle("Editable Java Source Code (" + cls.getFullName() + ")");
		codeArea.setText(cls.getCodeInfo().getCodeStr());
		codeArea.setCaretPosition(0);
		btnRebuild.setEnabled(true);
		updateProgress(0, "Ready to edit class " + cls.getFullName());
		repaint();
	}

	public void setTargetResource(JResource res) {
		this.targetResource = res;
		this.targetClass = null;
		targetTypeLabel.setText("Resource:");
		targetLabel.setText(res.getName());
		targetLabel.setForeground(new Color(0x19, 0x76, 0xd2));

		if (res.getName().endsWith(".xml") || res.getName().endsWith("AndroidManifest.xml")) {
			codeArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
		} else if (res.getName().endsWith(".json")) {
			codeArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
		} else {
			codeArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
		}

		codeBorder.setTitle("Editable Decoded Resource (" + res.getName() + ")");
		codeArea.setText(res.getCodeInfo().getCodeStr());
		codeArea.setCaretPosition(0);
		btnRebuild.setEnabled(true);
		updateProgress(0, "Ready to edit resource " + res.getName());
		repaint();
	}

	public boolean loadManifestResource() {
		try {
			ResourceFile manifest = AndroidManifestParser.getAndroidManifest(mainWindow.getWrapper().getResources());
			if (manifest != null) {
				JResource res = new JResource(manifest, manifest.getDeobfName(), JResource.JResType.FILE);
				setTargetResource(res);
				return true;
			}
		} catch (Exception e) {
			LOG.error("Failed to load AndroidManifest.xml", e);
		}
		log("AndroidManifest.xml not found. Please open an APK containing a valid manifest.");
		return false;
	}

	private void updateProgress(int percent, String message) {
		SwingUtilities.invokeLater(() -> {
			progressBar.setValue(percent);
			progressBar.setString(percent + "% - " + message);
			statusDetailLabel.setText("Status: " + message);
			if (percent == 100) {
				stepBadge.setText("Complete");
				stepBadge.setForeground(new Color(0x2e, 0x7d, 0x32));
				progressBar.setForeground(new Color(0x2e, 0x7d, 0x32));
			} else if (percent > 0) {
				stepBadge.setText("Building (" + percent + "%)");
				stepBadge.setForeground(new Color(0x19, 0x76, 0xd2));
				progressBar.setForeground(new Color(0x19, 0x76, 0xd2));
			} else {
				stepBadge.setText("Ready");
				stepBadge.setForeground(new Color(0x75, 0x75, 0x75));
				progressBar.setForeground(new Color(0x19, 0x76, 0xd2));
			}
		});
		log(message);
	}

	private void startPatchAndRebuild() {
		Path apkPath = (Path) apkCombo.getSelectedItem();
		if (apkPath == null || !Files.exists(apkPath)) {
			JOptionPane.showMessageDialog(this, "Please select an APK file from the project.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (targetClass == null && targetResource == null) {
			JOptionPane.showMessageDialog(this, "No target class or resource specified.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		String editedContent = codeArea.getText();
		btnRebuild.setEnabled(false);
		btnOpenDist.setEnabled(false);
		logArea.setText("");
		updateProgress(1, "Starting live patch pipeline...");

		if (targetClass != null) {
			new Thread(() -> {
				try {
					outputApk = patcherManager.patchClassAndRebuild(apkPath, targetClass, editedContent, this::updateProgress);
					onBuildSuccess();
				} catch (Exception ex) {
					onBuildError(ex);
				} finally {
					SwingUtilities.invokeLater(() -> btnRebuild.setEnabled(true));
				}
			}).start();
		} else if (targetResource != null) {
			new Thread(() -> {
				try {
					outputApk = patcherManager.patchResourceAndRebuild(apkPath, targetResource.getName(), editedContent, this::updateProgress);
					onBuildSuccess();
				} catch (Exception ex) {
					onBuildError(ex);
				} finally {
					SwingUtilities.invokeLater(() -> btnRebuild.setEnabled(true));
				}
			}).start();
		}
	}

	private void onBuildSuccess() {
		SwingUtilities.invokeLater(() -> {
			progressBar.setValue(100);
			progressBar.setString("100% - Build & Sign Complete!");
			progressBar.setForeground(new Color(0x2e, 0x7d, 0x32));
			statusDetailLabel.setText("Status: Success! Modified APK ready.");
			stepBadge.setText("Success");
			stepBadge.setForeground(new Color(0x2e, 0x7d, 0x32));
			btnOpenDist.setEnabled(true);
			JOptionPane.showMessageDialog(LivePatchDialog.this,
					"Modified APK Successfully Built & Signed!\nSaved at:\n" + outputApk.toAbsolutePath(),
					"Build Complete", JOptionPane.INFORMATION_MESSAGE);
		});
	}

	private void onBuildError(Exception ex) {
		LOG.error("Live patch rebuild failed", ex);
		log("ERROR: " + ex.getMessage());
		SwingUtilities.invokeLater(() -> {
			progressBar.setValue(100);
			progressBar.setString("Build Failed: " + ex.getMessage());
			progressBar.setForeground(new Color(0xc6, 0x28, 0x28));
			statusDetailLabel.setText("Status: Error during build");
			stepBadge.setText("Failed");
			stepBadge.setForeground(new Color(0xc6, 0x28, 0x28));
			JOptionPane.showMessageDialog(LivePatchDialog.this,
					"Rebuild failed:\n" + ex.getMessage(),
					"Error", JOptionPane.ERROR_MESSAGE);
		});
	}

	private void openOutputFolder() {
		if (outputApk != null && Files.exists(outputApk.getParent())) {
			try {
				Desktop.getDesktop().open(outputApk.getParent().toFile());
			} catch (Exception e) {
				log("Failed to open folder: " + e.getMessage());
			}
		}
	}

	private void log(String msg) {
		SwingUtilities.invokeLater(() -> {
			logArea.append(msg + "\n");
			logArea.setCaretPosition(logArea.getDocument().getLength());
		});
	}
}
