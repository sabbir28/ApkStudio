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
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.gui.treemodel.JClass;
import jadx.gui.treemodel.JNode;
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
	private JLabel classLabel;
	private RSyntaxTextArea codeArea;
	private JTextArea logArea;
	private JButton btnRebuild;
	private JButton btnOpenDist;

	private JClass targetClass;
	private Path outputApk;

	public static void open(MainWindow mainWindow) {
		JClass currentCls = findCurrentOpenClass(mainWindow);
		new LivePatchDialog(mainWindow, currentCls).setVisible(true);
	}

	public static void openForClass(MainWindow mainWindow, JClass jClass) {
		new LivePatchDialog(mainWindow, jClass).setVisible(true);
	}

	private static JClass findCurrentOpenClass(MainWindow mainWindow) {
		ContentPanel activeTab = mainWindow.getTabbedPane().getSelectedContentPanel();
		if (activeTab != null) {
			JNode node = activeTab.getNode();
			if (node instanceof JClass) {
				return (JClass) node;
			}
		}
		return null;
	}

	public LivePatchDialog(MainWindow mainWindow, JClass initialClass) {
		super(mainWindow);
		this.mainWindow = mainWindow;
		this.targetClass = initialClass;
		this.patcherManager = new LivePatcherManager(mainWindow);
		this.apkFiles = filterApkFiles(mainWindow);

		initUI();
		loadClassCode();
	}

	private List<Path> filterApkFiles(MainWindow mainWindow) {
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**.{apk,xapk,apks,apkm}");
		return mainWindow.getProject().getFilePaths().stream()
				.filter(matcher::matches)
				.collect(Collectors.toList());
	}

	private void initUI() {
		setTitle("⚡ Live APK Patcher & Builder - Direct Java Code Editing");
		setLayout(new BorderLayout(5, 5));

		// Top panel: APK selector and Class info
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

		JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		row1.add(new JLabel("Target APK:"));
		apkCombo = new JComboBox<>(apkFiles.toArray(new Path[0]));
		apkCombo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
			String name = value != null ? value.getFileName().toString() : "None";
			return new NodeLabel(name);
		});
		row1.add(apkCombo);

		row1.add(Box.createHorizontalStrut(15));
		row1.add(new JLabel("Class:"));
		classLabel = new JLabel(targetClass != null ? targetClass.getFullName() : "No class selected");
		classLabel.setFont(new Font("Consolas", Font.BOLD, 12));
		classLabel.setForeground(new Color(0x38, 0x8e, 0x3c));
		row1.add(classLabel);

		topPanel.add(row1);
		add(topPanel, BorderLayout.NORTH);

		// Center: RSyntaxTextArea
		codeArea = new RSyntaxTextArea(25, 80);
		codeArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
		codeArea.setCodeFoldingEnabled(true);
		codeArea.setAntiAliasingEnabled(true);
		codeArea.setFont(new Font("Consolas", Font.PLAIN, 13));

		RTextScrollPane codeScrollPane = new RTextScrollPane(codeArea);
		codeScrollPane.setBorder(BorderFactory.createTitledBorder("Editable Java Source Code"));
		add(codeScrollPane, BorderLayout.CENTER);

		// Bottom panel: Log console and Action buttons
		JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
		bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 8, 10));

		logArea = new JTextArea(5, 80);
		logArea.setEditable(false);
		logArea.setFont(new Font("Consolas", Font.PLAIN, 11));
		logArea.setBackground(new Color(0x1e, 0x1e, 0x1e));
		logArea.setForeground(new Color(0xd4, 0xd4, 0xd4));
		JScrollPane logScrollPane = new JScrollPane(logArea);
		logScrollPane.setBorder(BorderFactory.createTitledBorder("Build Diagnostics & Progress"));
		bottomPanel.add(logScrollPane, BorderLayout.CENTER);

		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 5));
		btnRebuild = new JButton("⚡ Compile & Rebuild APK");
		btnRebuild.setFont(new Font("Segoe UI", Font.BOLD, 12));
		btnRebuild.addActionListener(e -> startPatchAndRebuild());

		btnOpenDist = new JButton("📁 Open Output Folder");
		btnOpenDist.setEnabled(false);
		btnOpenDist.addActionListener(e -> openOutputFolder());

		JButton btnClose = new JButton("Close");
		btnClose.addActionListener(e -> dispose());

		buttonRow.add(btnRebuild);
		buttonRow.add(btnOpenDist);
		buttonRow.add(btnClose);
		bottomPanel.add(buttonRow, BorderLayout.SOUTH);

		add(bottomPanel, BorderLayout.SOUTH);

		setSize(950, 700);
		setLocationRelativeTo(mainWindow);
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		UiUtils.addEscapeShortCutToDispose(this);
	}

	private void loadClassCode() {
		if (targetClass != null) {
			classLabel.setText(targetClass.getFullName());
			String code = targetClass.getCodeInfo().getCodeStr();
			codeArea.setText(code);
			codeArea.setCaretPosition(0);
			log("Ready to edit " + targetClass.getFullName() + ". Make your modifications above and click 'Compile & Rebuild APK'.");
		} else {
			log("No class currently active. Please open a class first or right-click any class and select '⚡ Edit & Patch to APK...'.");
			btnRebuild.setEnabled(false);
		}
	}

	private void startPatchAndRebuild() {
		Path apkPath = (Path) apkCombo.getSelectedItem();
		if (apkPath == null || !Files.exists(apkPath)) {
			JOptionPane.showMessageDialog(this, "Please select an APK file from the project.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (targetClass == null) {
			JOptionPane.showMessageDialog(this, "No target class specified.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		String editedJavaCode = codeArea.getText();
		btnRebuild.setEnabled(false);
		logArea.setText("");
		log("Starting live patch pipeline for: " + targetClass.getFullName());

		new Thread(() -> {
			try {
				outputApk = patcherManager.patchClassAndRebuild(apkPath, targetClass, editedJavaCode, this::log);
				SwingUtilities.invokeLater(() -> {
					btnOpenDist.setEnabled(true);
					JOptionPane.showMessageDialog(LivePatchDialog.this,
							"Modified APK Successfully Built & Signed!\nSaved at:\n" + outputApk.toAbsolutePath(),
							"Build Complete", JOptionPane.INFORMATION_MESSAGE);
				});
			} catch (Exception ex) {
				LOG.error("Live patch rebuild failed", ex);
				log("ERROR: " + ex.getMessage());
				SwingUtilities.invokeLater(() -> {
					JOptionPane.showMessageDialog(LivePatchDialog.this,
							"Rebuild failed:\n" + ex.getMessage(),
							"Error", JOptionPane.ERROR_MESSAGE);
				});
			} finally {
				SwingUtilities.invokeLater(() -> btnRebuild.setEnabled(true));
			}
		}).start();
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
