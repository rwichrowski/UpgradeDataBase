package com.diligentia.diff;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Widok porownania slownikow: po lewej lista zakresow (tabela / slownik) z licznikami,
 * po prawej wiersze wybranego zakresu (rozne komorki jako "lewa → prawa"), pod nimi szczegol wiersza kolumna po kolumnie.
 */
public class DataDiffPanel extends JPanel {

    // region Pola i konstruktor

    private static final ModuleComparer.Status[] STATUSES = { ModuleComparer.Status.DIFFERENT, ModuleComparer.Status.ONLY_LEFT, ModuleComparer.Status.ONLY_RIGHT, ModuleComparer.Status.SAME };
    private static final Color DIFF_CELL = new Color(0xFFE2B0);
    private static final Color ONLY_LEFT_ROW = new Color(0xEAF2FF);
    private static final Color ONLY_RIGHT_ROW = new Color(0xEBF8EB);
    private static final Color MUTED = new Color(0x888888);
    private static final String ARROW = "  →  ";
    private static final int COL_SELECT = 0;
    private static final int COL_STATUS = 1;
    private static final int FIRST_DATA_COLUMN = 2;
    private static final int SELECTED_COLUMN = 5;

    private final Consumer<String> summarySink;

    private final JTextField filterText = new JTextField(20);
    private final Map<ModuleComparer.Status, JCheckBox> statusFilters = new EnumMap<>(ModuleComparer.Status.class);
    private final JCheckBox onlyDiffColumns = new JCheckBox("Tylko kolumny z różnicami", true);
    private final JCheckBox hideEqualScopes = new JCheckBox("Ukryj zakresy bez różnic", true);

    private final ScopeTableModel scopeModel = new ScopeTableModel();
    private final JTable scopeTable = new JTable(scopeModel);
    private final TableRowSorter<ScopeTableModel> scopeSorter = new TableRowSorter<>(scopeModel);

    private final RowTableModel rowModel = new RowTableModel();
    private final JTable rowTable = new JTable(rowModel);
    private TableRowSorter<RowTableModel> rowSorter;

    private final DetailTableModel detailModel = new DetailTableModel();
    private final JTable detailTable = new JTable(detailModel);

    /** Wiersze zaznaczone do skryptu synchronizujacego - ze wszystkich zakresow. */
    private final Set<DataComparer.RowDiff> selectedRows = new HashSet<>();
    private final JButton selectVisibleButton = new JButton("Zaznacz widoczne");
    private final JButton selectAllButton = new JButton("Zaznacz wszystkie różnice");
    private final JButton clearSelectionButton = new JButton("Odznacz");
    private final JButton scriptToRightButton = new JButton("Skrypt → prawa");
    private final JButton scriptToLeftButton = new JButton("Skrypt → lewa");

    private DataDiffService.Result result;
    /** Zakres zaznaczony przed ponownym porownaniem - po nim zaznaczany jest znow ten sam. */
    private String preferredCaption;

    public DataDiffPanel(Consumer<String> summarySink) {
        super(new BorderLayout());
        this.summarySink = summarySink;
        add(buildFilterPanel(), BorderLayout.NORTH);
        add(buildTables(), BorderLayout.CENTER);
        add(buildSyncPanel(), BorderLayout.SOUTH);
        updateSyncButtons();
    }

    // endregion

    // region Uklad

    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 8));
        panel.add(new JLabel("Szukaj:"));
        panel.add(filterText);
        filterText.setToolTipText("Szuka w nazwach zakresów i w wartościach wierszy (obie strony)");

        for (ModuleComparer.Status status : STATUSES) {
            JCheckBox box = new JCheckBox(SchemaDiffWindow.statusCaption(status), status != ModuleComparer.Status.SAME);
            box.addActionListener(e -> applyFilter());
            statusFilters.put(status, box);
            panel.add(box);
        }

        onlyDiffColumns.setToolTipText("Pokazuje klucz i tylko te kolumny, w których w tym zakresie jest jakaś różnica; pełny wiersz zawsze w panelu na dole");
        onlyDiffColumns.addActionListener(e -> rebuildRows());
        hideEqualScopes.addActionListener(e -> applyFilter());
        panel.add(onlyDiffColumns);
        panel.add(hideEqualScopes);

        filterText.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { applyFilter(); }

            @Override
            public void removeUpdate(DocumentEvent e) { applyFilter(); }

            @Override
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        return panel;
    }

    private JPanel buildSyncPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY), BorderFactory.createEmptyBorder(2, 4, 0, 8)));

        JLabel caption = new JLabel("Skrypt synchronizujący:");
        caption.setFont(bold(caption.getFont()));
        selectVisibleButton.setToolTipText("Zaznacza widoczne po filtrach różnice w bieżącym zakresie");
        selectAllButton.setToolTipText("Zaznacza wszystkie różnice we wszystkich tabelach i słownikach");
        scriptToRightButton.setToolTipText("Skrypt, który w prawej bazie ustawia dane jak w lewej (INSERT / UPDATE / DELETE) – zapisywany do pliku, nic nie jest wykonywane");
        scriptToLeftButton.setToolTipText("Skrypt, który w lewej bazie ustawia dane jak w prawej (INSERT / UPDATE / DELETE) – zapisywany do pliku, nic nie jest wykonywane");
        scriptToRightButton.setFont(bold(scriptToRightButton.getFont()));
        scriptToLeftButton.setFont(bold(scriptToLeftButton.getFont()));

        selectVisibleButton.addActionListener(e -> selectVisible());
        selectAllButton.addActionListener(e -> selectAllDifferences());
        clearSelectionButton.addActionListener(e -> changeSelection(selectedRows::clear));
        scriptToRightButton.addActionListener(e -> generateScript(true));
        scriptToLeftButton.addActionListener(e -> generateScript(false));

        panel.add(caption);
        panel.add(selectVisibleButton);
        panel.add(selectAllButton);
        panel.add(clearSelectionButton);
        panel.add(scriptToRightButton);
        panel.add(scriptToLeftButton);
        return panel;
    }

    private JSplitPane buildTables() {
        scopeTable.setRowSorter(scopeSorter);
        scopeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scopeTable.setRowHeight(scopeTable.getRowHeight() + 4);
        scopeTable.setFillsViewportHeight(true);
        scopeTable.setDefaultRenderer(Object.class, new ScopeRenderer());
        scopeTable.setDefaultRenderer(Integer.class, new ScopeRenderer());
        int[] scopeWidths = { 260, 50, 50, 50, 50, 40 };
        for (int i = 0; i < scopeWidths.length; i++) scopeTable.getColumnModel().getColumn(i).setPreferredWidth(scopeWidths[i]);
        scopeTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showScope(selectedScope());
        });

        rowTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rowTable.setRowHeight(rowTable.getRowHeight() + 4);
        rowTable.setFillsViewportHeight(true);
        rowTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        rowTable.setDefaultRenderer(Object.class, new RowRenderer());
        rowTable.setDefaultRenderer(ModuleComparer.Status.class, new RowRenderer());
        rowTable.setDefaultRenderer(Boolean.class, new SelectRenderer(rowTable.getDefaultRenderer(Boolean.class)));
        rowTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) detailModel.setRow(rowModel.scope, selectedRow());
        });

        detailTable.setRowHeight(detailTable.getRowHeight() + 4);
        detailTable.setFillsViewportHeight(true);
        detailTable.setDefaultRenderer(Object.class, new DetailRenderer());
        detailTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        detailTable.getColumnModel().getColumn(1).setPreferredWidth(400);
        detailTable.getColumnModel().getColumn(2).setPreferredWidth(400);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(rowTable), new JScrollPane(detailTable));
        right.setResizeWeight(0.65);
        right.setBorder(null);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(scopeTable), right);
        split.setDividerLocation(470);
        split.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        return split;
    }

    // endregion

    // region Wynik

    public void showResult(DataDiffService.Result newResult) {
        result = newResult;
        selectedRows.clear();
        scriptToRightButton.setToolTipText("Skrypt, który w " + newResult.rightLabel + " ustawia dane jak w " + newResult.leftLabel + " – zapisywany do pliku, nic nie jest wykonywane");
        scriptToLeftButton.setToolTipText("Skrypt, który w " + newResult.leftLabel + " ustawia dane jak w " + newResult.rightLabel + " – zapisywany do pliku, nic nie jest wykonywane");
        Map<ModuleComparer.Status, Integer> counts = newResult.counts();
        for (Map.Entry<ModuleComparer.Status, JCheckBox> filter : statusFilters.entrySet()) {
            filter.getValue().setText(SchemaDiffWindow.statusCaption(filter.getKey()) + " (" + counts.getOrDefault(filter.getKey(), 0) + ")");
        }
        detailTable.getColumnModel().getColumn(1).setHeaderValue("Lewa: " + newResult.leftLabel);
        detailTable.getColumnModel().getColumn(2).setHeaderValue("Prawa: " + newResult.rightLabel);
        detailTable.getTableHeader().repaint();

        DataComparer.Scope previous = selectedScope();
        if (previous != null) preferredCaption = previous.caption;
        scopeModel.setScopes(newResult.scopes);
        applyFilter();
        updateSyncButtons();
    }

    public DataDiffService.Result getResult() { return result; }

    private void showScope(DataComparer.Scope scope) {
        if (scope == rowModel.scope && scope != null) return;
        rowModel.scope = scope;
        rebuildRows();
    }

    /** Przebudowa kolumn tabeli wierszy (zmiana zakresu albo przelacznika "tylko kolumny z roznicami"). */
    private void rebuildRows() {
        rowModel.setScope(rowModel.scope, onlyDiffColumns.isSelected());
        rowSorter = new TableRowSorter<>(rowModel);
        for (int i = 2; i < rowModel.getColumnCount(); i++) rowSorter.setComparator(i, DataComparer.NATURAL_ORDER);
        rowTable.setRowSorter(rowSorter);
        fitColumns(rowTable);
        if (rowTable.getColumnCount() > 0) rowTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        applyRowFilter();
        detailModel.setRow(rowModel.scope, null);
    }

    // endregion

    // region Filtrowanie

    private void applyFilter() {
        if (result == null) return;
        DataComparer.Scope current = selectedScope();
        String text = filterText.getText().trim().toLowerCase(Locale.ROOT);

        scopeSorter.setRowFilter(new RowFilter<ScopeTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends ScopeTableModel, ? extends Integer> entry) {
                DataComparer.Scope scope = scopeModel.get(entry.getIdentifier());
                if (hideEqualScopes.isSelected() && !scope.hasDifferences()) return false;
                return text.isEmpty() || scope.caption.toLowerCase(Locale.ROOT).contains(text) || scope.rows.stream().anyMatch(r -> rowMatches(r, text));
            }
        });

        if (current == null && preferredCaption != null) current = result.scopes.stream().filter(s -> s.caption.equals(preferredCaption)).findFirst().orElse(null);
        int modelRow = current == null ? -1 : result.scopes.indexOf(current);
        int viewRow = modelRow < 0 ? -1 : scopeTable.convertRowIndexToView(modelRow);
        if (viewRow < 0 && scopeTable.getRowCount() > 0) viewRow = 0;
        if (viewRow >= 0) scopeTable.setRowSelectionInterval(viewRow, viewRow);
        else showScope(null);
        applyRowFilter();
    }

    private void applyRowFilter() {
        if (rowSorter == null) return;
        String text = filterText.getText().trim().toLowerCase(Locale.ROOT);
        DataComparer.Scope scope = rowModel.scope;
        boolean scopeMatchesText = scope != null && scope.caption.toLowerCase(Locale.ROOT).contains(text);

        rowSorter.setRowFilter(new RowFilter<RowTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends RowTableModel, ? extends Integer> entry) {
                DataComparer.RowDiff row = rowModel.get(entry.getIdentifier());
                if (!statusFilters.get(row.status).isSelected()) return false;
                return text.isEmpty() || scopeMatchesText || rowMatches(row, text);
            }
        });
        updateSummary();
    }

    private static boolean rowMatches(DataComparer.RowDiff row, String text) {
        for (String[] values : new String[][] { row.left, row.right }) {
            if (values == null) continue;
            for (String value : values) if (value != null && value.toLowerCase(Locale.ROOT).contains(text)) return true;
        }
        return false;
    }

    private void updateSummary() {
        DataComparer.Scope scope = rowModel.scope;
        if (scope == null) {
            summarySink.accept(result == null ? " " : "Brak zakresów do pokazania");
            return;
        }
        String note = scope.note == null ? "" : " – " + scope.note;
        summarySink.accept(scope.caption + ": widoczne " + rowTable.getRowCount() + " z " + rowModel.getRowCount() + note);
    }

    // endregion

    // region Skrypt synchronizujacy

    private void selectVisible() {
        if (rowModel.scope == null) return;
        changeSelection(() -> {
            for (int row = 0; row < rowTable.getRowCount(); row++) {
                DataComparer.RowDiff diff = rowModel.get(rowTable.convertRowIndexToModel(row));
                if (diff.status != ModuleComparer.Status.SAME) selectedRows.add(diff);
            }
        });
    }

    private void selectAllDifferences() {
        if (result == null) return;
        changeSelection(() -> result.scopes.forEach(scope -> scope.rows.stream().filter(r -> r.status != ModuleComparer.Status.SAME).forEach(selectedRows::add)));
    }

    /** Zmiana zaznaczenia z odswiezeniem checkboxow, licznikow zakresow i przyciskow. */
    private void changeSelection(Runnable change) {
        change.run();
        if (rowModel.getRowCount() > 0) rowModel.fireTableRowsUpdated(0, rowModel.getRowCount() - 1);
        scopeTable.repaint();
        updateSyncButtons();
    }

    private void updateSyncButtons() {
        int count = selectedRows.size();
        String left = result == null ? "lewa" : result.leftLabel;
        String right = result == null ? "prawa" : result.rightLabel;
        scriptToRightButton.setText("Skrypt " + left + " → " + right + " (" + count + ")");
        scriptToLeftButton.setText("Skrypt " + right + " → " + left + " (" + count + ")");
        scriptToRightButton.setEnabled(count > 0);
        scriptToLeftButton.setEnabled(count > 0);
        clearSelectionButton.setEnabled(count > 0);
        selectVisibleButton.setEnabled(result != null);
        selectAllButton.setEnabled(result != null);
    }

    /** Generuje skrypt dla zaznaczonych wierszy, zapisuje go obok raportu i pokazuje; niczego nie wykonuje na bazie. */
    private void generateScript(boolean toRight) {
        if (result == null || selectedRows.isEmpty()) return;
        DataSyncScript generator = new DataSyncScript(result, toRight);
        String script = generator.build(selectedRows);

        Path file;
        try {
            file = generator.write(script);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Nie udało się zapisać skryptu: " + e.getMessage(), "Błąd", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JTextArea area = new JTextArea(script);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(1100, 520));

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.add(new JLabel("Zapisano: " + file + "   (instrukcji: " + generator.statementCount() + ")"), BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        String target = toRight ? result.rightLabel : result.leftLabel;
        Object[] options = { "Otwórz plik", "Kopiuj do schowka", "Zamknij" };
        int answer = JOptionPane.showOptionDialog(this, content, "Skrypt synchronizujący → " + target, JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[2]);
        if (answer == 0) openFile(file);
        else if (answer == 1) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(script), null);
    }

    private void openFile(Path file) {
        try {
            Desktop.getDesktop().open(file.toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Nie udało się otworzyć " + file + ": " + e.getMessage(), "Błąd", JOptionPane.ERROR_MESSAGE);
        }
    }

    // endregion

    // region Pomocnicze

    private DataComparer.Scope selectedScope() {
        int row = scopeTable.getSelectedRow();
        return row < 0 ? null : scopeModel.get(scopeTable.convertRowIndexToModel(row));
    }

    private DataComparer.RowDiff selectedRow() {
        int row = rowTable.getSelectedRow();
        return row < 0 ? null : rowModel.get(rowTable.convertRowIndexToModel(row));
    }

    /** Szerokosc kolumn wg naglowka i tresci (pierwsze 300 wierszy), w granicach 60-420 px. */
    private static void fitColumns(JTable table) {
        for (int c = 0; c < table.getColumnCount(); c++) {
            TableColumn column = table.getColumnModel().getColumn(c);
            TableCellRenderer headerRenderer = table.getTableHeader().getDefaultRenderer();
            int width = headerRenderer.getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, c).getPreferredSize().width;
            for (int r = 0; r < Math.min(table.getRowCount(), 300); r++) {
                width = Math.max(width, table.prepareRenderer(table.getCellRenderer(r, c), r, c).getPreferredSize().width);
            }
            column.setPreferredWidth(Math.max(60, Math.min(420, width + 12)));
        }
    }

    private static Font bold(Font font) { return font.deriveFont(Font.BOLD); }

    // endregion

    // region Modele tabel

    /** Lista zakresow: tabela albo slownik z licznikami statusow i liczba wierszy zaznaczonych do skryptu. */
    private final class ScopeTableModel extends AbstractTableModel {

        private final String[] columns = { "Tabela / słownik", "Różne", "T. lewa", "T. prawa", "Ident.", "✔" };

        private List<DataComparer.Scope> scopes = new ArrayList<>();

        void setScopes(List<DataComparer.Scope> newScopes) {
            scopes = newScopes;
            fireTableDataChanged();
        }

        DataComparer.Scope get(int modelRow) { return scopes.get(modelRow); }

        @Override
        public int getRowCount() { return scopes.size(); }

        @Override
        public int getColumnCount() { return columns.length; }

        @Override
        public String getColumnName(int column) { return columns[column]; }

        @Override
        public Class<?> getColumnClass(int column) { return column == 0 ? String.class : Integer.class; }

        @Override
        public Object getValueAt(int row, int column) {
            DataComparer.Scope scope = scopes.get(row);
            if (column == 0) return scope.note == null ? scope.caption : scope.caption + " – " + scope.note;
            if (column == SELECTED_COLUMN) return (int) scope.rows.stream().filter(selectedRows::contains).count();
            return scope.counts().getOrDefault(STATUSES[column - 1], 0);
        }
    }

    /** Wiersze jednego zakresu; kolumna 0 to zaznaczenie do skryptu, 1 status, dalej widoczne kolumny zakresu. */
    private final class RowTableModel extends AbstractTableModel {

        private DataComparer.Scope scope;
        private int[] visibleColumns = new int[0];

        void setScope(DataComparer.Scope newScope, boolean onlyDifferent) {
            scope = newScope;
            visibleColumns = newScope == null ? new int[0] : chooseColumns(newScope, onlyDifferent);
            fireTableStructureChanged();
        }

        /** Klucz zawsze; reszta wszystkie albo tylko rozniace sie (gdy jakas sie rozni). Kolumna grupujaca pominieta - jest w nazwie zakresu. */
        private int[] chooseColumns(DataComparer.Scope scope, boolean onlyDifferent) {
            boolean[] differing = scope.differingColumns();
            boolean anyDiffering = false;
            for (boolean d : differing) anyDiffering |= d;

            List<Integer> chosen = new ArrayList<>();
            for (int i : scope.keyColumnIndexes) if (i != scope.groupColumnIndex) chosen.add(i);
            for (int i = 0; i < scope.columns.size(); i++) {
                if (i == scope.groupColumnIndex || scope.isKeyColumn(i)) continue;
                if (!onlyDifferent || !anyDiffering || differing[i]) chosen.add(i);
            }
            return chosen.stream().mapToInt(Integer::intValue).toArray();
        }

        DataComparer.RowDiff get(int modelRow) { return scope.rows.get(modelRow); }

        int scopeColumn(int column) { return visibleColumns[column - FIRST_DATA_COLUMN]; }

        boolean isDataColumn(int column) { return column >= FIRST_DATA_COLUMN; }

        boolean isDifferent(int modelRow, int column) {
            DataComparer.RowDiff row = get(modelRow);
            return isDataColumn(column) && row.differs != null && row.differs[scopeColumn(column)];
        }

        @Override
        public int getRowCount() { return scope == null ? 0 : scope.rows.size(); }

        @Override
        public int getColumnCount() { return scope == null ? 0 : visibleColumns.length + FIRST_DATA_COLUMN; }

        @Override
        public String getColumnName(int column) {
            if (column == COL_SELECT) return "✔";
            return column == COL_STATUS ? "Status" : scope.columns.get(scopeColumn(column));
        }

        @Override
        public Class<?> getColumnClass(int column) {
            if (column == COL_SELECT) return Boolean.class;
            return column == COL_STATUS ? ModuleComparer.Status.class : String.class;
        }

        /** Identycznych wierszy nie ma czego synchronizowac - bez checkboxa. */
        @Override
        public boolean isCellEditable(int row, int column) { return column == COL_SELECT && get(row).status != ModuleComparer.Status.SAME; }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column != COL_SELECT) return;
            DataComparer.RowDiff diff = get(row);
            if (Boolean.TRUE.equals(value)) selectedRows.add(diff);
            else selectedRows.remove(diff);
            fireTableCellUpdated(row, column);
            scopeTable.repaint();
            updateSyncButtons();
        }

        @Override
        public Object getValueAt(int row, int column) {
            DataComparer.RowDiff diff = get(row);
            if (column == COL_SELECT) return diff.status == ModuleComparer.Status.SAME ? null : selectedRows.contains(diff);
            if (column == COL_STATUS) return diff.status;
            int index = scopeColumn(column);
            if (isDifferent(row, column)) return DataComparer.format(diff.left[index]) + ARROW + DataComparer.format(diff.right[index]);
            return DataComparer.format(diff.any()[index]);
        }
    }

    /** Szczegol jednego wiersza: kolumna, wartosc lewa, wartosc prawa. */
    private static final class DetailTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = { "Kolumna", "Lewa", "Prawa" };

        private DataComparer.Scope scope;
        private DataComparer.RowDiff row;

        void setRow(DataComparer.Scope newScope, DataComparer.RowDiff newRow) {
            scope = newScope;
            row = newRow;
            fireTableDataChanged();
        }

        boolean isDifferent(int index) { return row != null && row.differs != null && row.differs[index]; }

        boolean isKey(int index) { return scope != null && scope.isKeyColumn(index); }

        boolean isMissing(int column) { return row != null && (column == 1 ? row.left == null : column == 2 && row.right == null); }

        @Override
        public int getRowCount() { return row == null ? 0 : scope.columns.size(); }

        @Override
        public int getColumnCount() { return COLUMNS.length; }

        @Override
        public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int index, int column) {
            if (column == 0) return scope.columns.get(index);
            String[] values = column == 1 ? row.left : row.right;
            return values == null ? "—" : DataComparer.format(values[index]);
        }
    }

    // endregion

    // region Renderery

    /** Zakres z roznicami pogrubiony, bez roznic wyszarzony; zera w licznikach wyszarzone. */
    private final class ScopeRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            DataComparer.Scope scope = scopeModel.get(table.convertRowIndexToModel(row));
            boolean zero = value instanceof Integer && (Integer) value == 0;
            setHorizontalAlignment(column == 0 ? LEFT : RIGHT);
            setFont(column == 0 && scope.hasDifferences() ? bold(table.getFont()) : table.getFont());
            if (!isSelected) setForeground(zero || !scope.hasDifferences() ? MUTED : scope.note != null ? new Color(0xB00020) : table.getForeground());
            if (!isSelected && column > 0 && !zero && column < 4) setBackground(SchemaDiffWindow.statusColor(STATUSES[column - 1]));
            else if (!isSelected) setBackground(table.getBackground());
            setToolTipText(column == 0 ? scope.caption : null);
            return this;
        }
    }

    /** Status kolorem jak w porownaniu obiektow, rozne komorki pomaranczowe, wiersze jednostronne lekko podbarwione, klucz pogrubiony. */
    private final class RowRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int viewColumn) {
            int modelRow = table.convertRowIndexToModel(row);
            int column = table.convertColumnIndexToModel(viewColumn);
            DataComparer.RowDiff diff = rowModel.get(modelRow);
            Object shown = column == COL_STATUS ? SchemaDiffWindow.statusCaption(diff.status) : value;
            super.getTableCellRendererComponent(table, shown, isSelected, hasFocus, row, viewColumn);

            boolean key = rowModel.isDataColumn(column) && rowModel.scope.isKeyColumn(rowModel.scopeColumn(column));
            setFont(key ? bold(table.getFont()) : table.getFont());
            setToolTipText(rowModel.isDifferent(modelRow, column) ? "Lewa: " + DataComparer.format(diff.left[rowModel.scopeColumn(column)]) + "   Prawa: " + DataComparer.format(diff.right[rowModel.scopeColumn(column)]) : null);
            if (isSelected) return this;

            setForeground("NULL".equals(value) ? MUTED : table.getForeground());
            if (column == COL_STATUS) setBackground(SchemaDiffWindow.statusColor(diff.status));
            else if (rowModel.isDifferent(modelRow, column)) setBackground(DIFF_CELL);
            else if (diff.status == ModuleComparer.Status.ONLY_LEFT) setBackground(ONLY_LEFT_ROW);
            else if (diff.status == ModuleComparer.Status.ONLY_RIGHT) setBackground(ONLY_RIGHT_ROW);
            else setBackground(table.getBackground());
            return this;
        }
    }

    /** Checkbox tylko w wierszach z roznica; w identycznych pusta komorka. */
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

    /** Szczegol: rozniace sie kolumny pomaranczowe, klucz pogrubiony, brak strony wyszarzony. */
    private final class DetailRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setFont(detailModel.isKey(row) ? bold(table.getFont()) : table.getFont());
            setToolTipText(column > 0 ? String.valueOf(value) : null);
            if (isSelected) return this;

            setForeground(detailModel.isMissing(column) || "NULL".equals(value) ? MUTED : table.getForeground());
            setBackground(detailModel.isDifferent(row) ? DIFF_CELL : table.getBackground());
            return this;
        }
    }

    // endregion
}
