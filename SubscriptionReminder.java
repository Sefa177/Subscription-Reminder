import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;

public class SubscriptionReminder {
    private static final Color BG = new Color(245, 247, 250);
    private static final Color INK = new Color(29, 39, 54);
    private static final Color MUTED = new Color(105, 117, 132);
    private static final Color BLUE = new Color(52, 105, 225);
    private static final DateTimeFormatter DATE_VIEW = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), ".subscription-reminder");
    private static final Path DATA_FILE = DATA_DIR.resolve("subscriptions.xml");

    private final List<Subscription> subscriptions = new ArrayList<>();
    private final Set<String> notifiedToday = new HashSet<>();
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Subscription", "Amount", "Next payment", "Cycle", "Remind before", "Status"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(tableModel);
    private final JLabel summary = new JLabel(" ");
    private final JLabel notice = new JLabel(" ");
    private TrayIcon trayIcon;

    private enum Period {
        MONTHLY("Monthly"), YEARLY("Yearly");
        final String label;
        Period(String label) { this.label = label; }
        @Override public String toString() { return label; }
        LocalDate advance(LocalDate date) { return this == MONTHLY ? date.plusMonths(1) : date.plusYears(1); }
    }

    private static final class Subscription {
        String id, name, currency;
        BigDecimal amount;
        LocalDate nextDate;
        Period period;
        int remindDays;

        Subscription(String id, String name, BigDecimal amount, String currency,
                     LocalDate nextDate, Period period, int remindDays) {
            this.id = id; this.name = name; this.amount = amount; this.currency = currency;
            this.nextDate = nextDate; this.period = period; this.remindDays = remindDays;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new SubscriptionReminder().show();
        });
    }

    private void show() {
        load();
        JFrame frame = new JFrame("Subscription Reminder");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(830, 520));
        frame.setSize(980, 620);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(buildContent());
        setupTray(frame);
        refresh();
        frame.setVisible(true);
        checkReminders();
        new javax.swing.Timer(60 * 60 * 1000, e -> checkReminders()).start();
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(24, 28, 20, 28));

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setOpaque(false);
        JLabel title = new JLabel("Subscription Reminder");
        title.setFont(new Font("Segoe UI", Font.BOLD, 28));
        title.setForeground(INK);
        JLabel subtitle = new JLabel("Keep your recurring payments and renewal dates in one place.");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        subtitle.setForeground(MUTED);
        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        titleStack.add(title); titleStack.add(Box.createVerticalStrut(5)); titleStack.add(subtitle);
        top.add(titleStack, BorderLayout.WEST);
        summary.setFont(new Font("Segoe UI", Font.BOLD, 14));
        summary.setForeground(BLUE);
        summary.setHorizontalAlignment(SwingConstants.RIGHT);
        top.add(summary, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        table.setRowHeight(38);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        table.setForeground(INK);
        table.setBackground(Color.WHITE);
        table.setSelectionBackground(new Color(225, 235, 255));
        table.setSelectionForeground(INK);
        table.setShowVerticalLines(false);
        table.setGridColor(new Color(232, 236, 242));
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setBackground(new Color(239, 242, 247));
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(224, 229, 237)));
        root.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 10));
        bottom.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton add = button("+ Add subscription", true);
        JButton edit = button("Edit", false);
        JButton paid = button("Mark paid · advance date", false);
        JButton delete = button("Delete", false);
        add.addActionListener(e -> addSubscription());
        edit.addActionListener(e -> editSelected());
        paid.addActionListener(e -> markPaid());
        delete.addActionListener(e -> deleteSelected());
        buttons.add(add); buttons.add(edit); buttons.add(paid); buttons.add(delete);
        bottom.add(buttons, BorderLayout.NORTH);
        notice.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        notice.setForeground(MUTED);
        bottom.add(notice, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private JButton button(String text, boolean primary) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(new EmptyBorder(10, 14, 10, 14));
        if (primary) {
            b.setBackground(BLUE); b.setForeground(Color.WHITE); b.setOpaque(true);
        } else {
            b.setBackground(Color.WHITE); b.setForeground(INK); b.setOpaque(true);
            b.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(220, 226, 235)), new EmptyBorder(9, 13, 9, 13)));
        }
        return b;
    }

    private void addSubscription() {
        Subscription value = editDialog(null);
        if (value != null) { subscriptions.add(value); save(); refresh(); checkReminders(); }
    }

    private void editSelected() {
        Subscription old = selected();
        if (old == null) return;
        Subscription value = editDialog(old);
        if (value != null) {
            old.name = value.name; old.amount = value.amount; old.currency = value.currency;
            old.nextDate = value.nextDate; old.period = value.period; old.remindDays = value.remindDays;
            save(); refresh(); checkReminders();
        }
    }

    private Subscription editDialog(Subscription existing) {
        JTextField name = new JTextField(existing == null ? "" : existing.name, 22);
        JTextField amount = new JTextField(existing == null ? "" : existing.amount.toPlainString(), 10);
        JComboBox<String> currency = new JComboBox<>(new String[]{"TRY", "USD", "EUR"});
        if (existing != null) currency.setSelectedItem(existing.currency);
        JTextField date = new JTextField(existing == null ? LocalDate.now().plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                : existing.nextDate.format(DateTimeFormatter.ISO_LOCAL_DATE), 12);
        JComboBox<Period> period = new JComboBox<>(Period.values());
        if (existing != null) period.setSelectedItem(existing.period);
        JSpinner reminder = new JSpinner(new SpinnerNumberModel(existing == null ? 3 : existing.remindDays, 0, 30, 1));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(8, 4, 4, 4));
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(6, 5, 6, 5); c.anchor = GridBagConstraints.WEST;
        addField(form, c, 0, "Subscription name", name);
        JPanel money = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0)); money.add(amount); money.add(currency);
        addField(form, c, 1, "Amount", money);
        addField(form, c, 2, "Next payment (YYYY-MM-DD)", date);
        addField(form, c, 3, "Billing cycle", period);
        addField(form, c, 4, "Remind me how many days ahead?", reminder);

        while (true) {
            int result = JOptionPane.showConfirmDialog(null, form,
                    existing == null ? "Add subscription" : "Edit subscription",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return null;
            try {
                String cleanName = name.getText().trim().replace('\t', ' ').replace('\n', ' ');
                if (cleanName.isEmpty()) throw new IllegalArgumentException("Subscription name cannot be empty.");
                BigDecimal price = new BigDecimal(amount.getText().trim().replace(',', '.'));
                if (price.signum() < 0) throw new IllegalArgumentException("Amount cannot be negative.");
                LocalDate next = LocalDate.parse(date.getText().trim());
                return new Subscription(existing == null ? UUID.randomUUID().toString() : existing.id,
                        cleanName, price, (String) currency.getSelectedItem(), next,
                        (Period) period.getSelectedItem(), (Integer) reminder.getValue());
            } catch (Exception ex) {
                String message = ex instanceof DateTimeException ? "Enter the date as YYYY-MM-DD. Example: 2026-12-05"
                        : ex instanceof NumberFormatException ? "Enter the amount as a number. Example: 12.99"
                        : ex.getMessage();
                JOptionPane.showMessageDialog(null, message, "Check the details", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void addField(JPanel panel, GridBagConstraints c, int row, String label, Component field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    private Subscription selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= subscriptions.size()) {
            JOptionPane.showMessageDialog(null, "Select a subscription from the list first.", "Selection required", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return subscriptions.get(row);
    }

    private void markPaid() {
        Subscription s = selected(); if (s == null) return;
        do { s.nextDate = s.period.advance(s.nextDate); } while (!s.nextDate.isAfter(LocalDate.now()));
        notifiedToday.remove(s.id); save(); refresh();
    }

    private void deleteSelected() {
        Subscription s = selected(); if (s == null) return;
        int answer = JOptionPane.showConfirmDialog(null, "“" + s.name + "” subscription?",
                "Delete subscription", JOptionPane.YES_NO_OPTION);
        if (answer == JOptionPane.YES_OPTION) { subscriptions.remove(s); save(); refresh(); }
    }

    private void refresh() {
        subscriptions.sort(Comparator.comparing(s -> s.nextDate));
        tableModel.setRowCount(0);
        LocalDate today = LocalDate.now();
        long dueSoon = 0;
        for (Subscription s : subscriptions) {
            long days = ChronoUnit.DAYS.between(today, s.nextDate);
            if (days <= s.remindDays) dueSoon++;
            String status = days < 0 ? "Overdue " + Math.abs(days) + " days"
                    : days == 0 ? "Today" : days + " days left";
            tableModel.addRow(new Object[]{s.name, money(s), s.nextDate.format(DATE_VIEW), s.period.label,
                    s.remindDays + " days before", status});
        }
        summary.setText(subscriptions.size() + " subscriptions · " + dueSoon + " upcoming payments");
        if (subscriptions.isEmpty()) notice.setText("No subscriptions yet. Add one to get started.");
        else notice.setText("Reminders appear in the Windows notification area while the app is running. Your data stays on this computer.");
    }

    private String money(Subscription s) { return s.amount.toPlainString() + " " + s.currency; }

    private void setupTray(JFrame frame) {
        if (!SystemTray.isSupported()) return;
        try {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(BLUE); g.fillOval(1, 1, 14, 14); g.setColor(Color.WHITE); g.fillOval(5, 5, 6, 6); g.dispose();
            trayIcon = new TrayIcon(image, "Subscription Reminder"); trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> { frame.setVisible(true); frame.setState(Frame.NORMAL); frame.toFront(); });
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open Subscription Reminder");
            open.addActionListener(e -> { frame.setVisible(true); frame.setState(Frame.NORMAL); frame.toFront(); });
            MenuItem exit = new MenuItem("Quit");
            exit.addActionListener(e -> { SystemTray.getSystemTray().remove(trayIcon); frame.dispose(); System.exit(0); });
            menu.add(open); menu.addSeparator(); menu.add(exit);
            trayIcon.setPopupMenu(menu);
            SystemTray.getSystemTray().add(trayIcon);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) { frame.setVisible(false); }
            });
        } catch (Exception ignored) { trayIcon = null; }
    }

    private void checkReminders() {
        LocalDate today = LocalDate.now();
        for (Subscription s : subscriptions) {
            long days = ChronoUnit.DAYS.between(today, s.nextDate);
            if (days <= s.remindDays && notifiedToday.add(s.id + ":" + today)) {
                String when = days < 0 ? Math.abs(days) + " days overdue"
                        : days == 0 ? "renews today" : "renews in " + days + " days";
                if (trayIcon != null) trayIcon.displayMessage("Subscription reminder", s.name + " · " + money(s) + " · " + when,
                        TrayIcon.MessageType.INFO);
            }
        }
    }

    private void load() {
        if (!Files.exists(DATA_FILE)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(DATA_FILE)) {
            props.loadFromXML(in);
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("item.")) continue;
                try {
                    String decoded = new String(Base64.getDecoder().decode(props.getProperty(key)), StandardCharsets.UTF_8);
                    String[] f = decoded.split("\\t", -1);
                    subscriptions.add(new Subscription(f[0], f[1], new BigDecimal(f[2]), f[3],
                            LocalDate.parse(f[4]), Period.valueOf(f[5]), Integer.parseInt(f[6])));
                } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Could not load saved subscriptions:\n" + ex.getMessage(),
                    "Storage error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        Properties props = new Properties();
        for (Subscription s : subscriptions) {
            String record = String.join("\t", s.id, s.name, s.amount.toPlainString(), s.currency,
                    s.nextDate.toString(), s.period.name(), Integer.toString(s.remindDays));
            props.setProperty("item." + s.id, Base64.getEncoder().encodeToString(record.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            Files.createDirectories(DATA_DIR);
            try (OutputStream out = Files.newOutputStream(DATA_FILE)) { props.storeToXML(out, "Subscription Reminder", "UTF-8"); }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, "Could not save data:\n" + ex.getMessage(),
                    "Storage error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
