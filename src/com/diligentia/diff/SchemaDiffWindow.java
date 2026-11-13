package com.diligentia.diff;

import com.diligentia.SqlExecutor;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.JTextComponent;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Okno porownania: wybor dwoch stron (srodowisko z targets.txt albo katalog), lista rozjechanych obiektow
 * z filtrami i podglad roznic w WinMerge (dwuklik / Enter).
 */
public class SchemaDiffWindow extends JFrame {

    // region Pola i konstruktor

    private static final int MAX_RECENT_FOLDERS = 15;
    private static final String ICON_RESOURCE = "/icons/app-icon.png";
    private static final int[] ICON_SIZES = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };
    private static final String ALL_TYPES = "Wszystkie typy";
    private static final String[] TYPES = { ALL_TYPES, "PROCEDURE", "FUNCTION", "VIEW", "TRIGGER" };
    private static final int COL_SELECT = 0;
    private static final int COL_STATUS = 1;
    private static final int COL_LEFT = 4;
    private static final int COL_RIGHT = 5;

    private static final String PREF_LEFT = "left";
    private static final String PREF_RIGHT = "right";
    private static final String PREF_RECENT = "recentFolders";
    private static final String PREF_IGNORE_WS = "ignoreWhitespace";
    private static final String PREF_IGNORE_CASE = "ignoreCase";
    private static final String PREF_WINMERGE = "winMergePath";
    private static final String PREF_GIT_EXTENSIONS = "gitExtensionsPath";
    private static final String PREF_BOUNDS = "bounds";

    private final Preferences prefs = Preferences.userNodeForPackage(SchemaDiffWindow.class);

    private final JComboBox<String> leftSource = new JComboBox<>();
    private final JComboBox<String> rightSource = new JComboBox<>();
    private final JLabel leftBranch = new JLabel();
    private final JLabel rightBranch = new JLabel();
    private final JCheckBox ignoreWhitespace = new JCheckBox("Ignoruj białe znaki");
    private final JCheckBox ignoreCase = new JCheckBox("Ignoruj wielkość liter");
    private final JButton compareButton = new JButton("Porównaj");

    private final JTextField filterText = new JTextField(24);
    private final JComboBox<String> typeFilter = new JComboBox<>(TYPES);
    private final Map<ModuleComparer.Status, JCheckBox> statusFilters = new EnumMap<>(ModuleComparer.Status.class);

    private final EntryTableModel tableModel = new EntryTableModel();
    private final JTable table = new JTable(tableModel);
    private final TableRowSorter<EntryTableModel> sorter = new TableRowSorter<>(tableModel);

    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel summaryLabel = new JLabel(" ");
    private final JButton warningsButton = new JButton("Ostrzeżenia");
    private final JButton folderCompareButton = new JButton("Wszystko w WinMerge");
    private final JButton reportButton = new JButton("Raport");

    private final JPanel deployPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final JButton selectVisibleButton = new JButton("Zaznacz widoczne");
    private final JButton clearSelectionButton = new JButton("Odznacz wszystkie");
    private final JButton deployButton = new JButton("Tylko przygotuj pliki");
    private final JButton deployNowButton = new JButton("Wdróż teraz");

    private final List<JComponent> disabledWhileBusy = new ArrayList<>();
    private final List<String> environments = new ArrayList<>();
    private SchemaDiffService.Result result;

    public SchemaDiffWindow() {
        super("UpgradeDataBase – porównanie obiektów bazy");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        applyWindowIcon();
        buildLayout();
        loadSettings();
        updateResultButtons();
        refreshBranchLabels();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { saveSettings(); }

            @Override
            public void windowActivated(WindowEvent e) { refreshBranchLabels(); }
        });
    }

    /** Otwiera okno w watku Swinga z wygladem systemu. */
    public static void open() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // zostaje domyslny wyglad Swinga
            }
            new SchemaDiffWindow().setVisible(true);
        });
    }

    // endregion

    // region Uklad okna

    private void buildLayout() {
        JPanel top = new JPanel(new BorderLayout());
        top.add(buildSourcesPanel(), BorderLayout.NORTH);
        top.add(buildFilterPanel(), BorderLayout.SOUTH);

        configureTable();

        JPanel statusRow = new JPanel(new BorderLayout(8, 0));
        statusRow.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        statusRow.add(statusLabel, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(summaryLabel);
        buttons.add(warningsButton);
        buttons.add(reportButton);
        buttons.add(folderCompareButton);
        statusRow.add(buttons, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(buildDeployPanel(), BorderLayout.NORTH);
        bottom.add(statusRow, BorderLayout.SOUTH);

        warningsButton.addActionListener(e -> showWarnings());
        reportButton.addActionListener(e -> openFile(result == null ? null : result.reportFile()));
        folderCompareButton.addActionListener(e -> openFolderCompare());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
    }

    private JPanel buildSourcesPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        leftSource.setEditable(true);
        rightSource.setEditable(true);
        leftSource.setToolTipText("Środowisko z targets.txt albo katalog z plikami .sql");
        rightSource.setToolTipText("Środowisko z targets.txt albo katalog z plikami .sql");

        JButton browseLeft = new JButton("…");
        JButton browseRight = new JButton("…");
        browseLeft.setToolTipText("Wybierz katalog");
        browseRight.setToolTipText("Wybierz katalog");
        browseLeft.addActionListener(e -> browseFolder(leftSource));
        browseRight.addActionListener(e -> browseFolder(rightSource));

        JButton swap = new JButton("⇅ Zamień");
        swap.addActionListener(e -> swapSources());

        compareButton.setFont(compareButton.getFont().deriveFont(Font.BOLD));
        compareButton.addActionListener(e -> runCompare());
        getRootPane().setDefaultButton(compareButton);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.fill = GridBagConstraints.HORIZONTAL;

        for (JLabel branchLabel : Arrays.asList(leftBranch, rightBranch)) {
            branchLabel.setPreferredSize(new Dimension(200, branchLabel.getPreferredSize().height));
            branchLabel.setFont(branchLabel.getFont().deriveFont(Font.BOLD));
        }
        watchSource(leftSource);
        watchSource(rightSource);

        addCell(panel, new JLabel("Lewa:"), c, 0, 0, 0);
        addCell(panel, leftSource, c, 1, 0, 1);
        addCell(panel, browseLeft, c, 2, 0, 0);
        addCell(panel, leftBranch, c, 3, 0, 0);
        addCell(panel, new JLabel("Prawa:"), c, 0, 1, 0);
        addCell(panel, rightSource, c, 1, 1, 1);
        addCell(panel, browseRight, c, 2, 1, 0);
        addCell(panel, rightBranch, c, 3, 1, 0);

        c.gridheight = 2;
        c.fill = GridBagConstraints.BOTH;
        addCell(panel, swap, c, 4, 0, 0);
        addCell(panel, compareButton, c, 5, 0, 0);
        c.gridheight = 1;

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        options.add(ignoreWhitespace);
        options.add(ignoreCase);
        c.gridwidth = 4;
        addCell(panel, options, c, 1, 2, 1);

        disabledWhileBusy.addAll(Arrays.asList(leftSource, rightSource, browseLeft, browseRight, swap, compareButton, ignoreWhitespace, ignoreCase));
        return panel;
    }

    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 8));

        panel.add(new JLabel("Szukaj:"));
        panel.add(filterText);
        panel.add(typeFilter);

        for (ModuleComparer.Status status : ModuleComparer.Status.values()) {
            JCheckBox box = new JCheckBox(statusCaption(status), status != ModuleComparer.Status.SAME);
            box.addActionListener(e -> applyFilter());
            statusFilters.put(status, box);
            panel.add(box);
        }

        filterText.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { applyFilter(); }

            @Override
            public void removeUpdate(DocumentEvent e) { applyFilter(); }

            @Override
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        typeFilter.addActionListener(e -> applyFilter());
        return panel;
    }

    private JPanel buildDeployPanel() {
        deployPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY), BorderFactory.createEmptyBorder(2, 4, 0, 8)));

        JLabel caption = new JLabel("Wdrożenie z repozytorium:");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD));
        deployNowButton.setFont(deployNowButton.getFont().deriveFont(Font.BOLD));
        selectVisibleButton.setToolTipText("Zaznacza wszystkie widoczne po filtrach obiekty, które mają plik w repozytorium");
        deployButton.setToolTipText("Zapisuje scripts_paths.txt i targets.txt bez wykonywania skryptów");
        deployNowButton.setToolTipText("Zapisuje scripts_paths.txt i targets.txt i od razu wykonuje skrypty (SqlExecutor)");

        selectVisibleButton.addActionListener(e -> selectVisible());
        clearSelectionButton.addActionListener(e -> tableModel.clearSelection());
        deployButton.addActionListener(e -> prepareDeployment(false));
        deployNowButton.addActionListener(e -> prepareDeployment(true));
        tableModel.setSelectionListener(this::updateDeployPanel);

        deployPanel.add(caption);
        deployPanel.add(selectVisibleButton);
        deployPanel.add(clearSelectionButton);
        deployPanel.add(deployButton);
        deployPanel.add(deployNowButton);
        deployPanel.setVisible(false);
        return deployPanel;
    }

    private void configureTable() {
        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(table.getRowHeight() + 4);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(COL_SELECT).setCellRenderer(new SelectRenderer(table.getDefaultRenderer(Boolean.class)));
        table.getColumnModel().getColumn(COL_SELECT).setMaxWidth(40);

        int[] widths = { 32, 110, 90, 380, 320, 320 };
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                boolean onCheckbox = table.columnAtPoint(e.getPoint()) == COL_SELECT;
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && !onCheckbox) openSelectedInWinMerge();
            }

            @Override
            public void mousePressed(MouseEvent e) { showPopupIfTrigger(e); }

            @Override
            public void mouseReleased(MouseEvent e) { showPopupIfTrigger(e); }
        });

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openWinMerge");
        table.getActionMap().put("openWinMerge", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { openSelectedInWinMerge(); }
        });

        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "showFileHistory");
        table.getActionMap().put("showFileHistory", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { showSelectedFileHistory(); }
        });
    }

    private JPopupMenu buildPopup(ModuleComparer.Entry entry) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem winMerge = new JMenuItem("Porównaj w WinMerge (Enter)");
        winMerge.setFont(winMerge.getFont().deriveFont(Font.BOLD));
        winMerge.addActionListener(e -> openSelectedInWinMerge());
        menu.add(winMerge);
        menu.addSeparator();

        Path leftFile = result.left.sourceFile(entry.left);
        Path rightFile = result.right.sourceFile(entry.right);
        menu.add(menuItem("Otwórz plik źródłowy – lewa", leftFile != null, () -> openFile(leftFile)));
        menu.add(menuItem("Otwórz plik źródłowy – prawa", rightFile != null, () -> openFile(rightFile)));
        menu.addSeparator();

        Path leftHistoryFile = gitTrackedFile(entry.left);
        Path rightHistoryFile = gitTrackedFile(entry.right);
        menu.add(menuItem("Historia pliku w Git Extensions – lewa (H)", leftHistoryFile != null, () -> showFileHistory(leftHistoryFile)));
        menu.add(menuItem("Historia pliku w Git Extensions – prawa (H)", rightHistoryFile != null, () -> showFileHistory(rightHistoryFile)));
        menu.addSeparator();
        menu.add(menuItem("Kopiuj nazwę obiektu", true, () -> copyToClipboard(entry.any().getDisplayName())));
        menu.add(menuItem("Kopiuj ścieżkę pliku – lewa", leftFile != null, () -> copyToClipboard(String.valueOf(leftFile))));
        menu.add(menuItem("Kopiuj ścieżkę pliku – prawa", rightFile != null, () -> copyToClipboard(String.valueOf(rightFile))));
        return menu;
    }

    // endregion

    // region Porownanie

    private void runCompare() {
        String leftSpec = sourceText(leftSource);
        String rightSpec = sourceText(rightSource);
        if (leftSpec.isEmpty() || rightSpec.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Wybierz obie strony porównania.", "Porównanie", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!confirmBranch(leftSpec) || !confirmBranch(rightSpec)) return;

        boolean ws = ignoreWhitespace.isSelected();
        boolean ic = ignoreCase.isSelected();
        rememberFolder(leftSpec);
        rememberFolder(rightSpec);
        setBusy(true);

        new SwingWorker<SchemaDiffService.Result, String>() {
            @Override
            protected SchemaDiffService.Result doInBackground() throws Exception {
                return SchemaDiffService.compare(leftSpec, rightSpec, ws, ic, message -> publish(message.trim()));
            }

            @Override
            protected void process(List<String> messages) {
                statusLabel.setText(messages.get(messages.size() - 1));
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    showResult(get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    statusLabel.setText("Błąd: " + cause.getMessage());
                    JOptionPane.showMessageDialog(SchemaDiffWindow.this, cause.getMessage(), "Błąd porównania", JOptionPane.ERROR_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void showResult(SchemaDiffService.Result newResult) {
        result = newResult;
        tableModel.setResult(newResult);
        table.getColumnModel().getColumn(COL_LEFT).setHeaderValue("Lewa: " + newResult.left.label + (newResult.left.branch == null ? "" : " [" + newResult.left.branch + "]"));
        table.getColumnModel().getColumn(COL_RIGHT).setHeaderValue("Prawa: " + newResult.right.label + (newResult.right.branch == null ? "" : " [" + newResult.right.branch + "]"));
        table.getTableHeader().repaint();

        Map<ModuleComparer.Status, Integer> counts = SchemaDiffService.countByStatus(newResult.entries);
        for (Map.Entry<ModuleComparer.Status, JCheckBox> filter : statusFilters.entrySet()) {
            filter.getValue().setText(statusCaption(filter.getKey()) + " (" + counts.getOrDefault(filter.getKey(), 0) + ")");
        }

        statusLabel.setText(String.format("Lewa: %d obiektów, prawa: %d obiektów. Raport: %s", newResult.left.modules.size(), newResult.right.modules.size(), newResult.reportFile()));
        updateResultButtons();
        updateDeployPanel();
        applyFilter();
    }

    // endregion

    // region Wdrozenie

    /** Pasek wdrozenia widoczny tylko dla porownania baza <-> repozytorium; przycisk pokazuje liczbe i cel. */
    private void updateDeployPanel() {
        boolean available = DeploymentPreparer.isRepositoryComparison(result);
        deployPanel.setVisible(available);
        if (!available) return;

        int count = tableModel.selectedCount();
        deployButton.setText("Tylko przygotuj pliki (" + count + ")");
        deployNowButton.setText("Wdróż teraz (" + count + ") → " + DeploymentPreparer.databaseSide(result).label);
        deployButton.setEnabled(count > 0);
        deployNowButton.setEnabled(count > 0);
        clearSelectionButton.setEnabled(count > 0);
    }

    private void selectVisible() {
        List<ModuleComparer.Entry> visible = new ArrayList<>();
        for (int row = 0; row < table.getRowCount(); row++) visible.add(tableModel.get(table.convertRowIndexToModel(row)));
        tableModel.select(visible);
    }

    /** Pokazuje plan (pliki, srodowisko, uwagi), po potwierdzeniu zapisuje scripts_paths.txt i targets.txt, a dla deployNow od razu wykonuje skrypty. */
    private void prepareDeployment(boolean deployNow) {
        DeploymentPreparer.Plan plan = DeploymentPreparer.buildPlan(result, tableModel.selectedEntries());
        if (plan.files.isEmpty()) return;

        Path repositoryRoot = Paths.get(plan.repositoryDescription);
        String comparedBranch = DeploymentPreparer.repositorySide(result).branch;
        String currentBranch = GitBranch.of(repositoryRoot);
        if (currentBranch != null && !currentBranch.equals(comparedBranch)) plan.warnings.add(0, "Gałąź repozytorium zmieniła się od porównania (" + comparedBranch + " → " + currentBranch + ") – wgrane zostaną pliki z " + currentBranch + ". Porównaj ponownie przed wdrożeniem.");

        StringBuilder sb = new StringBuilder();
        sb.append("Środowisko docelowe: ").append(plan.environment).append('\n');
        sb.append("Repozytorium: ").append(plan.repositoryDescription).append('\n');
        if (currentBranch != null) sb.append("Gałąź: ").append(currentBranch).append(GitBranch.isMainBranch(currentBranch) ? "" : "   <-- nie master").append('\n');
        sb.append('\n');
        sb.append("Pliki do wgrania (").append(plan.files.size()).append("), w tej kolejności:\n");
        plan.files.forEach(f -> sb.append("  ").append(f.startsWith(repositoryRoot) ? repositoryRoot.relativize(f) : f).append('\n'));
        if (!plan.warnings.isEmpty()) {
            sb.append("\nUwagi:\n");
            plan.warnings.forEach(w -> sb.append("  - ").append(w).append('\n'));
        }
        sb.append("\nZostaną nadpisane:\n");
        sb.append("  ").append(DeploymentPreparer.SCRIPTS_FILE).append(" – lista powyższych plików\n");
        sb.append("  ").append(SchemaDiffService.TARGETS_FILE).append(" – aktywne zostanie tylko ").append(plan.environment).append(", pozostałe środowiska zostaną zakomentowane\n\n");
        sb.append(deployNow ? "Po potwierdzeniu skrypty zostaną OD RAZU wykonane na " + plan.environment + "." : "Samo wdrożenie uruchamiasz osobno: SqlExecutor bez argumentów.");

        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(900, 420));

        String confirmLabel = deployNow ? "Wykonaj na " + plan.environment : "Zapisz pliki";
        Object[] options = { confirmLabel, "Anuluj" };
        String title = (deployNow ? "Wdrożenie → " : "Przygotowanie wdrożenia → ") + plan.environment;
        int answer = JOptionPane.showOptionDialog(this, scroll, title, JOptionPane.OK_CANCEL_OPTION, deployNow ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (answer != 0) return;

        try {
            DeploymentPreparer.write(plan, Paths.get(DeploymentPreparer.SCRIPTS_FILE), Paths.get(SchemaDiffService.TARGETS_FILE));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Błąd przygotowania wdrożenia", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (deployNow) {
            runDeployment(plan.environment);
            return;
        }

        statusLabel.setText("Przygotowano wdrożenie " + plan.files.size() + " plików na " + plan.environment + ".");
        JOptionPane.showMessageDialog(this, "Zapisano " + DeploymentPreparer.SCRIPTS_FILE + " (" + plan.files.size() + " plików) i " + SchemaDiffService.TARGETS_FILE + " (aktywne: " + plan.environment + ").\n\nUruchom wdrożenie: SqlExecutor bez argumentów (w IntelliJ konfiguracja SqlExecutor\nalbo java -jar .\\target\\UpgradeDataBase-1.0-SNAPSHOT.jar).\nPotem kliknij Porównaj, żeby sprawdzić wynik.", "Wdrożenie przygotowane", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Wykonuje skrypty z zapisanych przed chwila scripts_paths.txt / targets.txt (jak SqlExecutor bez argumentow) z logiem na zywo. */
    private void runDeployment(String environment) {
        JTextArea log = new JTextArea();
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JLabel state = new JLabel("Trwa wdrożenie na " + environment + "...");
        state.setFont(state.getFont().deriveFont(Font.BOLD));
        JButton recompare = new JButton("Porównaj ponownie");
        JButton close = new JButton("Zamknij");
        recompare.setEnabled(false);

        JDialog dialog = new JDialog(this, "Wdrożenie → " + environment, false);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        south.add(state, BorderLayout.CENTER);
        JPanel dialogButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        dialogButtons.add(recompare);
        dialogButtons.add(close);
        south.add(dialogButtons, BorderLayout.EAST);
        dialog.getContentPane().add(new JScrollPane(log), BorderLayout.CENTER);
        dialog.getContentPane().add(south, BorderLayout.SOUTH);
        dialog.setSize(1000, 500);
        dialog.setLocationRelativeTo(this);

        close.addActionListener(e -> dialog.dispose());
        recompare.addActionListener(e -> {
            dialog.dispose();
            runCompare();
        });

        setBusy(true);
        deployPanel.setEnabled(false);
        deployButton.setEnabled(false);
        deployNowButton.setEnabled(false);
        dialog.setVisible(true);

        new SwingWorker<Void, String>() {
            private int okCount;
            private int errorCount;

            @Override
            protected Void doInBackground() throws Exception {
                List<SqlExecutor.DbTarget> targets = SqlExecutor.readTargets(SchemaDiffService.TARGETS_FILE);
                List<File> scripts = SqlExecutor.readScriptPaths(DeploymentPreparer.SCRIPTS_FILE);
                try (PrintStream out = new PrintStream(new LineOutputStream(this::onLine), true, StandardCharsets.UTF_8.name())) {
                    SqlExecutor.deploy(targets, scripts, out);
                }
                return null;
            }

            private void onLine(String line) {
                if (line.startsWith("OK")) okCount++;
                else if (line.startsWith("ERR")) errorCount++;
                publish(line);
            }

            @Override
            protected void process(List<String> lines) {
                lines.forEach(line -> log.append(line + "\n"));
                log.setCaretPosition(log.getDocument().getLength());
            }

            @Override
            protected void done() {
                setBusy(false);
                updateDeployPanel();
                recompare.setEnabled(true);
                try {
                    get();
                    state.setText(String.format("Zakończono: OK %d, błędy %d", okCount, errorCount));
                    state.setForeground(errorCount > 0 ? new Color(0xB00020) : new Color(0x1B7F1B));
                    statusLabel.setText(String.format("Wdrożenie na %s: OK %d, błędy %d. Kliknij Porównaj, żeby sprawdzić wynik.", environment, okCount, errorCount));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    state.setText("Błąd: " + cause.getMessage());
                    state.setForeground(new Color(0xB00020));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    // endregion

    // region Filtrowanie

    private void applyFilter() {
        String text = filterText.getText().trim().toLowerCase(Locale.ROOT);
        String type = (String) typeFilter.getSelectedItem();

        sorter.setRowFilter(new RowFilter<EntryTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends EntryTableModel, ? extends Integer> row) {
                return matchesFilter(tableModel.get(row.getIdentifier()), text, type);
            }
        });

        if (result != null) summaryLabel.setText("Widoczne: " + table.getRowCount() + " z " + tableModel.getRowCount());
    }

    private boolean matchesFilter(ModuleComparer.Entry entry, String text, String type) {
        if (!statusFilters.get(entry.status).isSelected()) return false;
        if (type != null && !ALL_TYPES.equals(type) && !type.equals(entry.any().getType())) return false;
        return text.isEmpty() || entry.key.contains(text);
    }

    // endregion

    // region Narzedzia zewnetrzne

    private void openSelectedInWinMerge() {
        ModuleComparer.Entry entry = selectedEntry();
        if (entry == null) return;

        try {
            Path[] files = SchemaDiffService.prepareFiles(result, entry);
            String name = entry.any().getDisplayName();
            String leftDescription = result.left.description + " | " + name + (entry.left == null ? " (brak)" : "");
            String rightDescription = result.right.description + " | " + name + (entry.right == null ? " (brak)" : "");
            launchWinMerge(WinMerge.fileArgs(leftDescription, rightDescription, files[0], files[1]));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Błąd", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openFolderCompare() {
        if (result == null) return;
        launchWinMerge(WinMerge.folderArgs(result.left.description, result.right.description, result.leftDir, result.rightDir));
    }

    /** Uruchamia WinMerge; gdy go nie ma pod znana sciezka, pyta o WinMergeU.exe i zapamietuje wybor. */
    private void launchWinMerge(List<String> args) {
        launchTool("WinMerge", "WinMergeU.exe", PREF_WINMERGE, WinMerge.find(prefs.get(PREF_WINMERGE, null)), exe -> WinMerge.start(exe, args));
    }

    /** Klawisz H: historia pliku z repozytorium dla zaznaczonego wiersza - po jednym oknie dla kazdej strony, ktora ma plik w gicie. */
    private void showSelectedFileHistory() {
        ModuleComparer.Entry entry = selectedEntry();
        if (entry == null) return;

        List<Path> files = new ArrayList<>();
        for (SqlModule module : Arrays.asList(entry.left, entry.right)) {
            Path file = gitTrackedFile(module);
            if (file != null) files.add(file);
        }

        if (files.isEmpty()) {
            statusLabel.setText(entry.any().getDisplayName() + " nie ma pliku w repozytorium git – brak historii.");
            return;
        }
        files.forEach(this::showFileHistory);
    }

    /** Historia pliku z repozytorium (wszystkie commity, ktore go zmienialy) w Git Extensions. */
    private void showFileHistory(Path file) {
        launchTool("Git Extensions", "GitExtensions.exe", PREF_GIT_EXTENSIONS, GitExtensions.find(prefs.get(PREF_GIT_EXTENSIONS, null)), exe -> GitExtensions.fileHistory(exe, file));
    }

    /** Uruchamia narzedzie; gdy go nie ma pod znana sciezka ani w PATH, pyta o plik exe i zapamietuje wybor. */
    private void launchTool(String toolName, String exeName, String prefKey, String exe, ToolStarter starter) {
        try {
            starter.start(exe);
            return;
        } catch (IOException ignored) {
            // nie ma narzedzia w typowych miejscach ani w PATH - zapytaj uzytkownika
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Wskaż " + exeName);
        chooser.setFileFilter(new FileNameExtensionFilter(exeName, "exe"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        String chosen = chooser.getSelectedFile().getAbsolutePath();
        prefs.put(prefKey, chosen);
        try {
            starter.start(chosen);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Nie udało się uruchomić " + toolName + ": " + e.getMessage(), "Błąd", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Uruchomienie narzedzia zewnetrznego dla podanej sciezki exe. */
    private interface ToolStarter {
        void start(String exe) throws IOException;
    }

    // endregion

    // region Ustawienia

    private void loadSettings() {
        try {
            for (SqlExecutor.DbTarget target : SchemaDiffService.readAllTargets()) environments.add(target.getEnv());
        } catch (Exception e) {
            statusLabel.setText("Nie udało się odczytać " + SchemaDiffService.TARGETS_FILE + ": " + e.getMessage());
        }
        if (environments.isEmpty()) statusLabel.setText("Brak " + SchemaDiffService.TARGETS_FILE + " w katalogu roboczym " + Paths.get("").toAbsolutePath() + " – można porównywać tylko katalogi.");

        refreshSourceItems();
        leftSource.setSelectedItem(prefs.get(PREF_LEFT, environments.isEmpty() ? "" : environments.get(0)));
        rightSource.setSelectedItem(prefs.get(PREF_RIGHT, ""));
        ignoreWhitespace.setSelected(prefs.getBoolean(PREF_IGNORE_WS, false));
        ignoreCase.setSelected(prefs.getBoolean(PREF_IGNORE_CASE, false));

        int[] bounds = parseBounds(prefs.get(PREF_BOUNDS, ""));
        if (bounds != null) {
            setBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        } else {
            setSize(new Dimension(1300, 750));
            setLocationRelativeTo(null);
        }
    }

    private void saveSettings() {
        prefs.put(PREF_LEFT, sourceText(leftSource));
        prefs.put(PREF_RIGHT, sourceText(rightSource));
        prefs.putBoolean(PREF_IGNORE_WS, ignoreWhitespace.isSelected());
        prefs.putBoolean(PREF_IGNORE_CASE, ignoreCase.isSelected());
        if ((getExtendedState() & MAXIMIZED_BOTH) == 0) prefs.put(PREF_BOUNDS, getX() + "," + getY() + "," + getWidth() + "," + getHeight());
    }

    /** Zapamietuje katalog (nie srodowisko) na liscie ostatnich, najnowszy na gorze. */
    private void rememberFolder(String spec) {
        if (isEnvironment(spec) || !Files.isDirectory(Paths.get(spec))) return;

        Set<String> folders = new LinkedHashSet<>();
        folders.add(spec);
        folders.addAll(recentFolders());
        prefs.put(PREF_RECENT, folders.stream().limit(MAX_RECENT_FOLDERS).collect(Collectors.joining("\n")));
        refreshSourceItems();
    }

    private List<String> recentFolders() {
        String stored = prefs.get(PREF_RECENT, "");
        return stored.isEmpty() ? new ArrayList<>() : Arrays.asList(stored.split("\n"));
    }

    /** Lista podpowiedzi: najpierw srodowiska z targets.txt, potem ostatnie katalogi; wpisany tekst zostaje. */
    private void refreshSourceItems() {
        for (JComboBox<String> combo : Arrays.asList(leftSource, rightSource)) {
            String current = sourceText(combo);
            combo.removeAllItems();
            environments.forEach(combo::addItem);
            recentFolders().forEach(combo::addItem);
            combo.setSelectedItem(current);
        }
    }

    // endregion

    // region Galaz git

    /** Etykieta galezi odswiezana przy kazdej zmianie strony (wybor z listy albo wpisywanie). */
    private void watchSource(JComboBox<String> combo) {
        combo.addActionListener(e -> refreshBranchLabels());
        Component editor = combo.getEditor().getEditorComponent();
        if (!(editor instanceof JTextComponent)) return;

        ((JTextComponent) editor).getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { refreshBranchLabels(); }

            @Override
            public void removeUpdate(DocumentEvent e) { refreshBranchLabels(); }

            @Override
            public void changedUpdate(DocumentEvent e) { refreshBranchLabels(); }
        });
    }

    private void refreshBranchLabels() {
        showBranch(leftBranch, sourceText(leftSource));
        showBranch(rightBranch, sourceText(rightSource));
    }

    private void showBranch(JLabel label, String spec) {
        String branch = branchOf(spec);
        if (branch == null) {
            label.setText("");
            label.setToolTipText(null);
            return;
        }
        boolean main = GitBranch.isMainBranch(branch);
        label.setText("gałąź: " + branch);
        label.setForeground(main ? new Color(0x1B7F1B) : new Color(0xC05000));
        label.setToolTipText(main ? "Repozytorium na gałęzi głównej" : "Repozytorium NIE jest na master – porównanie dotyczy gałęzi " + branch);
    }

    /** Galaz git dla strony bedacej katalogiem; null dla srodowiska, nieistniejacej sciezki i katalogu spoza repo. */
    private String branchOf(String spec) {
        if (spec.isEmpty() || isEnvironment(spec)) return null;
        try {
            Path folder = Paths.get(spec);
            return Files.isDirectory(folder) ? GitBranch.of(folder) : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Plik obiektu, gdy pochodzi z katalogu w repozytorium git (tylko taki ma historie); null dla bazy i plikow spoza gita. */
    private static Path gitTrackedFile(SqlModule module) {
        if (module == null || !module.isFromFile()) return null;
        Path file = Paths.get(module.getOrigin());
        return GitBranch.of(file.getParent()) != null ? file : null;
    }

    /** Dla katalogu na galezi innej niz master/main pyta, czy porownywac z ta galezia. */
    private boolean confirmBranch(String spec) {
        String branch = branchOf(spec);
        if (branch == null || GitBranch.isMainBranch(branch)) return true;

        String message = "Katalog:\n" + spec + "\n\njest na gałęzi: " + branch + " (nie master).\n\nPorównujesz z gałęzią " + branch + ". Czy kontynuować?";
        return JOptionPane.showConfirmDialog(this, message, "Gałąź " + branch, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    // endregion

    // region Pomocnicze

    /** Ikona okna z /icons/app-icon.png w kilku rozmiarach (pasek tytulu, pasek zadan, Alt+Tab); bez pliku zostaje ikona Javy. */
    private void applyWindowIcon() {
        URL url = SchemaDiffWindow.class.getResource(ICON_RESOURCE);
        if (url == null) return;

        try {
            BufferedImage source = ImageIO.read(url);
            if (source == null) return;
            List<Image> icons = new ArrayList<>();
            for (int size : ICON_SIZES) icons.add(scaleIcon(source, size));
            setIconImages(icons);
        } catch (IOException ignored) {
            // uszkodzony plik ikony - zostaje domyslna ikona
        }
    }

    private static BufferedImage scaleIcon(BufferedImage source, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return scaled;
    }

    private void setBusy(boolean busy) {
        disabledWhileBusy.forEach(c -> c.setEnabled(!busy));
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        if (busy) statusLabel.setText("Trwa porównanie...");
        if (!busy) updateResultButtons();
    }

    private void updateResultButtons() {
        boolean hasResult = result != null;
        folderCompareButton.setEnabled(hasResult);
        reportButton.setEnabled(hasResult);
        warningsButton.setEnabled(hasResult && !result.warnings.isEmpty());
        warningsButton.setText(hasResult ? "Ostrzeżenia (" + result.warnings.size() + ")" : "Ostrzeżenia");
    }

    private void showWarnings() {
        if (result == null) return;
        JTextArea area = new JTextArea(String.join("\n", result.warnings));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(1000, 450));
        JOptionPane.showMessageDialog(this, scroll, "Ostrzeżenia (" + result.warnings.size() + ")", JOptionPane.INFORMATION_MESSAGE);
    }

    private void browseFolder(JComboBox<String> combo) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File current = new File(sourceText(combo));
        if (current.isDirectory()) chooser.setCurrentDirectory(current);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) combo.setSelectedItem(chooser.getSelectedFile().getAbsolutePath());
    }

    private void swapSources() {
        String left = sourceText(leftSource);
        leftSource.setSelectedItem(sourceText(rightSource));
        rightSource.setSelectedItem(left);
    }

    private void openFile(Path file) {
        if (file == null) return;
        try {
            Desktop.getDesktop().open(file.toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Nie udało się otworzyć " + file + ": " + e.getMessage(), "Błąd", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showPopupIfTrigger(MouseEvent e) {
        if (!e.isPopupTrigger() || result == null) return;
        int row = table.rowAtPoint(new Point(e.getX(), e.getY()));
        if (row < 0) return;
        table.setRowSelectionInterval(row, row);
        buildPopup(selectedEntry()).show(table, e.getX(), e.getY());
    }

    private ModuleComparer.Entry selectedEntry() {
        int row = table.getSelectedRow();
        return row < 0 || result == null ? null : tableModel.get(table.convertRowIndexToModel(row));
    }

    private boolean isEnvironment(String spec) {
        return environments.stream().anyMatch(env -> env.equalsIgnoreCase(spec));
    }

    private static String sourceText(JComboBox<String> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    private static JMenuItem menuItem(String text, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    private static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static void addCell(JPanel panel, Component component, GridBagConstraints c, int x, int y, double weightX) {
        c.gridx = x;
        c.gridy = y;
        c.weightx = weightX;
        panel.add(component, c);
    }

    private static int[] parseBounds(String value) {
        String[] parts = value.split(",");
        if (parts.length != 4) return null;
        try {
            int[] bounds = new int[4];
            for (int i = 0; i < 4; i++) bounds[i] = Integer.parseInt(parts[i].trim());
            return bounds[2] > 200 && bounds[3] > 200 ? bounds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String statusCaption(ModuleComparer.Status status) {
        switch (status) {
            case DIFFERENT: return "Różni się";
            case ONLY_LEFT: return "Tylko lewa";
            case ONLY_RIGHT: return "Tylko prawa";
            case ENCRYPTED: return "Szyfrowane";
            case SAME: return "Identyczne";
            default: return status.getLabel();
        }
    }

    private static Color statusColor(ModuleComparer.Status status) {
        switch (status) {
            case DIFFERENT: return new Color(0xFFE2B0);
            case ONLY_LEFT: return new Color(0xCFE3FF);
            case ONLY_RIGHT: return new Color(0xD3F2D3);
            case ENCRYPTED: return new Color(0xE2E2E2);
            default: return Color.WHITE;
        }
    }

    // endregion

    // region Model tabeli

    /** Wiersze tabeli: jeden obiekt z wyniku porownania; zaznaczenie do wdrozenia tylko dla obiektow z plikiem w repo. */
    private static final class EntryTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = { "✔", "Status", "Typ", "Obiekt", "Lewa", "Prawa" };

        private SchemaDiffService.Result result;
        private List<ModuleComparer.Entry> entries = new ArrayList<>();
        private final Set<String> selectedKeys = new LinkedHashSet<>();
        private Runnable selectionListener = () -> { };

        void setResult(SchemaDiffService.Result newResult) {
            result = newResult;
            entries = newResult.entries;
            selectedKeys.clear();
            fireTableDataChanged();
            selectionListener.run();
        }

        void setSelectionListener(Runnable listener) { selectionListener = listener; }

        ModuleComparer.Entry get(int modelRow) { return entries.get(modelRow); }

        boolean isDeployable(ModuleComparer.Entry entry) { return DeploymentPreparer.repositoryModule(result, entry) != null; }

        int selectedCount() { return selectedKeys.size(); }

        List<ModuleComparer.Entry> selectedEntries() {
            return entries.stream().filter(e -> selectedKeys.contains(e.key)).collect(Collectors.toList());
        }

        void select(List<ModuleComparer.Entry> toSelect) {
            toSelect.stream().filter(this::isDeployable).forEach(e -> selectedKeys.add(e.key));
            fireSelectionChanged();
        }

        void clearSelection() {
            selectedKeys.clear();
            fireSelectionChanged();
        }

        private void fireSelectionChanged() {
            if (!entries.isEmpty()) fireTableRowsUpdated(0, entries.size() - 1);
            selectionListener.run();
        }

        @Override
        public int getRowCount() { return entries.size(); }

        @Override
        public int getColumnCount() { return COLUMNS.length; }

        @Override
        public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Class<?> getColumnClass(int column) {
            if (column == COL_SELECT) return Boolean.class;
            return column == COL_STATUS ? ModuleComparer.Status.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) { return column == COL_SELECT && isDeployable(entries.get(row)); }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column != COL_SELECT) return;
            String key = entries.get(row).key;
            if (Boolean.TRUE.equals(value)) selectedKeys.add(key);
            else selectedKeys.remove(key);
            fireTableCellUpdated(row, column);
            selectionListener.run();
        }

        @Override
        public Object getValueAt(int row, int column) {
            ModuleComparer.Entry entry = entries.get(row);
            switch (column) {
                case 0: return isDeployable(entry) ? selectedKeys.contains(entry.key) : null;
                case 1: return entry.status;
                case 2: return entry.any().getType();
                case 3: return entry.any().getDisplayName();
                case 4: return describe(result.left, entry.left);
                case 5: return describe(result.right, entry.right);
                default: return "";
            }
        }

        private static String describe(SchemaDiffService.Side side, SqlModule module) {
            if (module == null) return "—";
            if (module.isEncrypted()) return "(zaszyfrowany)";
            return module.isFromFile() ? side.relativeOrigin(module) : "zmieniony " + module.getModifyDate();
        }
    }

    /** Strumien dzielacy wyjscie SqlExecutor na linie (UTF-8, bez \r) i przekazujacy je dalej. */
    private static final class LineOutputStream extends OutputStream {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Consumer<String> sink;

        LineOutputStream(Consumer<String> sink) { this.sink = sink; }

        @Override
        public void write(int b) {
            if (b == '\n') emitLine();
            else if (b != '\r') buffer.write(b);
        }

        @Override
        public void close() {
            if (buffer.size() > 0) emitLine();
        }

        private void emitLine() {
            sink.accept(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            buffer.reset();
        }
    }

    /** Checkbox tylko w wierszach, ktore da sie wdrozyc; w pozostalych pusta komorka. */
    private static final class SelectRenderer implements TableCellRenderer {

        private final TableCellRenderer checkboxRenderer;
        private final JLabel empty = new JLabel();

        SelectRenderer(TableCellRenderer checkboxRenderer) {
            this.checkboxRenderer = checkboxRenderer;
            empty.setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            if (value != null) return checkboxRenderer.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            empty.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            return empty;
        }
    }

    /** Kolorowe tlo kolumny statusu. */
    private static final class StatusRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            ModuleComparer.Status status = (ModuleComparer.Status) value;
            super.getTableCellRendererComponent(table, statusCaption(status), isSelected, hasFocus, row, column);
            if (!isSelected) setBackground(statusColor(status));
            return this;
        }
    }

    // endregion
}
