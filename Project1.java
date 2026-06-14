// ============================================================
// CHAI-LIAR v7.0 - SINGLE-OWNER PRODUCTION EDITION
// Main class: ChaiLiarMain
// SQLite backend, automatic backups, archiving, crash-safe.
// ============================================================

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.sql.*;
import java.text.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.Timer;

public class ChaiLiarMain extends JFrame {
    public DashboardPanel dashboard;
    private MenuPanel menu;
    private OrderPanel orders;
    private HonestyPanel honesty;
    private LeaderboardPanel leaderboard;
    private ThemeManager themeManager;
    private TransactionLogPanel logPanel;
    private PersistenceManager persistence;
    private FeedbackSystem feedback;
    public SalesmanPanel salesmanPanel;
    private InventoryPanel inventory;
    public UserManager userManager;
    private AppSecurityManager securityManager;
    private SalesReportManager reportManager;
    public HappyHourManager happyHour;
    private JLabel userStatusLabel;

    public static final String DATA_DIR = "data";
    public static final String BACKUP_DIR = "backup_daily";
    public static final String ARCHIVE_DIR = DATA_DIR + "/archive";

    // SQLite connection
    private static Connection dbConnection;

    public static Connection getDBConnection() throws SQLException {
        if (dbConnection == null || dbConnection.isClosed()) {
            String url = "jdbc:sqlite:" + DATA_DIR + "/chailiar.db";
            dbConnection = DriverManager.getConnection(url);
            initializeDatabase();
        }
        return dbConnection;
    }

    private static void initializeDatabase() {
        try (Statement stmt = getDBConnection().createStatement()) {
            // Users table
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "username TEXT PRIMARY KEY, password_hash TEXT, salt TEXT, role TEXT, salesman_id TEXT, reg_date INTEGER)");
            // Menu items table
            stmt.execute("CREATE TABLE IF NOT EXISTS menu_items (" +
                    "name TEXT PRIMARY KEY, price INTEGER, category TEXT, available INTEGER, added_date INTEGER, added_by TEXT)");
            // Sales table (main)
            stmt.execute("CREATE TABLE IF NOT EXISTS sales (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, item_name TEXT, original_price INTEGER, final_price INTEGER, " +
                    "salesman_id TEXT, sold_by TEXT, timestamp INTEGER, is_free INTEGER)");
            // Inventory table
            stmt.execute("CREATE TABLE IF NOT EXISTS inventory (" +
                    "item_name TEXT PRIMARY KEY, quantity INTEGER)");
            // Free item log (audit)
            stmt.execute("CREATE TABLE IF NOT EXISTS free_logs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, item_name TEXT, salesman_id TEXT, authorized_by TEXT, timestamp INTEGER)");
            // Honesty score and total sales (singleton)
            stmt.execute("CREATE TABLE IF NOT EXISTS app_state (key TEXT PRIMARY KEY, int_value INTEGER)");
            // Happy hour config
            stmt.execute("CREATE TABLE IF NOT EXISTS happy_hour (start TEXT, end TEXT, discount INTEGER, enabled INTEGER)");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public ChaiLiarMain() {
        // Create directories
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            Files.createDirectories(Paths.get(BACKUP_DIR));
            Files.createDirectories(Paths.get(ARCHIVE_DIR));
        } catch (IOException e) { e.printStackTrace(); }

        // Automatic daily backup on startup
        performDailyBackup();

        setTitle("☕ CHAI-LIAR v7.0 | Production Edition");
        setSize(1400, 900);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Initialize managers (now database-backed)
        logPanel = new TransactionLogPanel();
        persistence = new PersistenceManager();
        feedback = new FeedbackSystem();
        userManager = new UserManager();
        securityManager = new AppSecurityManager();
        reportManager = new SalesReportManager();
        happyHour = new HappyHourManager();

        dashboard = new DashboardPanel(persistence, reportManager);
        menu = new MenuPanel(dashboard, logPanel, securityManager, reportManager, userManager, happyHour);
        orders = new OrderPanel(logPanel);
        leaderboard = new LeaderboardPanel();
        themeManager = new ThemeManager(this);
        inventory = new InventoryPanel(menu, logPanel);
        salesmanPanel = new SalesmanPanel(reportManager, securityManager, userManager, leaderboard);

        if (!showLoginDialog()) System.exit(0);

        honesty = new HonestyPanel(dashboard, logPanel, userManager, reportManager, securityManager, inventory);

        // Layout
        add(dashboard, BorderLayout.WEST);
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(menu, BorderLayout.CENTER);
        centerPanel.add(inventory, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);
        add(orders, BorderLayout.EAST);

        JTabbedPane southTabs = new JTabbedPane();
        User currentUser = userManager.getCurrentUser();
        if (currentUser != null && currentUser.getRole().equals("OWNER")) {
            southTabs.addTab("😇 Honesty Meter & Actions", honesty);
        }
        southTabs.addTab("📜 Transaction Log", logPanel);
        add(southTabs, BorderLayout.SOUTH);

        JPanel northPanel = new JPanel(new BorderLayout());
        JPanel topPanels = new JPanel(new GridLayout(1, 2, 10, 10));
        topPanels.add(leaderboard);
        topPanels.add(salesmanPanel);
        northPanel.add(topPanels, BorderLayout.CENTER);
        String username = currentUser != null ? currentUser.getUsername() : "Guest";
        String role = currentUser != null ? currentUser.getRole() : "None";
        userStatusLabel = new JLabel(" 👤 " + username + " | Role: " + role + " ");
        userStatusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        userStatusLabel.setBorder(BorderFactory.createEtchedBorder());
        userStatusLabel.setOpaque(true);
        userStatusLabel.setBackground(new Color(220, 240, 255));
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        statusPanel.add(userStatusLabel);
        northPanel.add(statusPanel, BorderLayout.NORTH);
        add(northPanel, BorderLayout.NORTH);

        // Load data from DB
        persistence.loadData(dashboard);
        reportManager.loadReports();
        happyHour.load();
        feedback.attachToDashboard(dashboard, logPanel);
        securityManager.logAction("Application started", currentUser);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveAll();
                System.exit(0);
            }
        });
    }

    private void performDailyBackup() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        File backupFile = new File(BACKUP_DIR + "/backup_" + today + ".db");
        if (!backupFile.exists()) {
            try {
                Files.copy(Paths.get(DATA_DIR + "/chailiar.db"), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logPanel.addEntry("Daily backup created: " + backupFile.getName());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void manualBackup() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File backup = new File(BACKUP_DIR + "/manual_" + timestamp + ".db");
        try {
            Files.copy(Paths.get(DATA_DIR + "/chailiar.db"), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            JOptionPane.showMessageDialog(this, "Backup created: " + backup.getPath(), "Backup Success", JOptionPane.INFORMATION_MESSAGE);
            logPanel.addEntry("Manual backup created: " + backup.getPath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Backup failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveAll() {
        persistence.saveData(dashboard.getTotalSales(), dashboard.getHonestyScore());
        userManager.saveUsers();
        menu.saveMenuItems();
        reportManager.saveReports();
        happyHour.save();
        logPanel.addEntry("Application closed – all data saved.");
    }

    public void pushUndo(Runnable undo) {
        if (orders != null) orders.pushUndo(undo);
    }

    private boolean showLoginDialog() {
        JDialog loginDialog = new JDialog(this, "Login - CHAI-LIAR", true);
        loginDialog.setLayout(new BorderLayout());
        loginDialog.setSize(400, 300);
        loginDialog.setLocationRelativeTo(this);
        JPanel loginPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        JTextField usernameField = new JTextField(15);
        JPasswordField passwordField = new JPasswordField(15);
        JComboBox<String> roleCombo = new JComboBox<>(new String[]{"OWNER", "SALESMAN"});
        gbc.gridx = 0; gbc.gridy = 0;
        loginPanel.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1;
        loginPanel.add(usernameField, gbc);
        gbc.gridx = 0; gbc.gridy = 1;
        loginPanel.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        loginPanel.add(passwordField, gbc);
        gbc.gridx = 0; gbc.gridy = 2;
        loginPanel.add(new JLabel("Role:"), gbc);
        gbc.gridx = 1;
        loginPanel.add(roleCombo, gbc);
        JButton loginBtn = new JButton("Login");
        JButton registerBtn = new JButton("Register New User");
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(loginBtn);
        buttonPanel.add(registerBtn);
        loginDialog.add(loginPanel, BorderLayout.CENTER);
        loginDialog.add(buttonPanel, BorderLayout.SOUTH);
        final boolean[] loginSuccess = {false};
        loginBtn.addActionListener(e -> {
            String username = usernameField.getText();
            String password = new String(passwordField.getPassword());
            String role = (String) roleCombo.getSelectedItem();
            User user = userManager.authenticate(username, password, role);
            if (user != null) {
                loginSuccess[0] = true;
                loginDialog.dispose();
            } else {
                JOptionPane.showMessageDialog(loginDialog,
                        "Invalid credentials or user not found!",
                        "Login Failed", JOptionPane.ERROR_MESSAGE);
            }
        });
        registerBtn.addActionListener(e -> {
            String username = usernameField.getText();
            String password = new String(passwordField.getPassword());
            String role = (String) roleCombo.getSelectedItem();
            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(loginDialog,
                        "Username and password cannot be empty!",
                        "Registration Failed", JOptionPane.ERROR_MESSAGE);
                return;
            }
            User newUser = new User(username, password, role);
            if (userManager.registerUser(newUser)) {
                JOptionPane.showMessageDialog(loginDialog,
                        "User registered successfully! Please login.",
                        "Registration Successful", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(loginDialog,
                        "Username already exists!",
                        "Registration Failed", JOptionPane.ERROR_MESSAGE);
            }
        });
        loginDialog.setVisible(true);
        return loginSuccess[0];
    }

    public void logout() {
        userManager.setCurrentUser(null);
        securityManager.logAction("User logged out", null);
        logPanel.addEntry("User logged out");
        JOptionPane.showMessageDialog(this, "Please restart the application.", "Logout", JOptionPane.INFORMATION_MESSAGE);
        System.exit(0);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChaiLiarMain().setVisible(true));
    }
}

// ============================================================
// UTILITY (password hashing) - unchanged
// ============================================================
class PasswordUtil {
    public static String hashPassword(String password, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] hashed = md.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }
}

// ============================================================
// USER CLASS (with DB storage)
// ============================================================
class User implements Serializable {
    private static final long serialVersionUID = 2L;
    private String username;
    private String passwordHash;
    private byte[] salt;
    private String role;
    private String salesmanId;
    private Date registrationDate;

    public User(String username, String plainPassword, String role) {
        this.username = username;
        this.salt = PasswordUtil.generateSalt();
        this.passwordHash = PasswordUtil.hashPassword(plainPassword, salt);
        this.role = role;
        this.registrationDate = new Date();
        if (role.equals("SALESMAN")) this.salesmanId = UUID.randomUUID().toString();
    }

    // For loading from DB
    public User(String username, String passwordHash, byte[] salt, String role, String salesmanId, Date regDate) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.role = role;
        this.salesmanId = salesmanId;
        this.registrationDate = regDate;
    }

    public boolean checkPassword(String plainPassword) {
        String hash = PasswordUtil.hashPassword(plainPassword, salt);
        return hash.equals(passwordHash);
    }

    public String getUsername() { return username; }
    public String getRole() { return role; }
    public String getSalesmanId() { return salesmanId; }
    public Date getRegistrationDate() { return registrationDate; }
    public byte[] getSalt() { return salt; }
    public String getPasswordHash() { return passwordHash; }

    public boolean hasPermission(String action) {
        if (role.equals("OWNER")) return true;
        if (role.equals("SALESMAN")) {
            return action.equals("SELL") || action.equals("VIEW_MENU") || action.equals("VIEW_SALES_REPORT");
        }
        return false;
    }
}

// ============================================================
// USER MANAGER (DB-based)
// ============================================================
class UserManager {
    private Map<String, User> users;
    private User currentUser;

    public UserManager() {
        users = new HashMap<>();
        loadUsers();
        if (users.isEmpty()) {
            User admin = new User("admin", "admin123", "OWNER");
            User salesman = new User("salesman", "sales123", "SALESMAN");
            registerUser(admin);
            registerUser(salesman);
        }
    }

    public boolean registerUser(User user) {
        if (users.containsKey(user.getUsername())) return false;
        users.put(user.getUsername(), user);
        saveUsers();
        return true;
    }

    public User authenticate(String username, String password, String role) {
        User user = users.get(username);
        if (user != null && user.checkPassword(password) && user.getRole().equals(role)) {
            currentUser = user;
            return user;
        }
        return null;
    }

    public User getCurrentUser() { return currentUser; }
    public void setCurrentUser(User u) { currentUser = u; }

    private void loadUsers() {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
            while (rs.next()) {
                String username = rs.getString("username");
                String hash = rs.getString("password_hash");
                byte[] salt = Base64.getDecoder().decode(rs.getString("salt"));
                String role = rs.getString("role");
                String salesmanId = rs.getString("salesman_id");
                long regMillis = rs.getLong("reg_date");
                Date regDate = new Date(regMillis);
                User user = new User(username, hash, salt, role, salesmanId, regDate);
                users.put(username, user);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void saveUsers() {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement()) {
            stmt.execute("DELETE FROM users");
            for (User u : users.values()) {
                String sql = String.format("INSERT INTO users VALUES ('%s', '%s', '%s', '%s', '%s', %d)",
                        u.getUsername(), u.getPasswordHash(), Base64.getEncoder().encodeToString(u.getSalt()),
                        u.getRole(), u.getSalesmanId() == null ? "" : u.getSalesmanId(),
                        u.getRegistrationDate().getTime());
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}

// ============================================================
// SECURITY MANAGER (log to file only, not DB)
// ============================================================
class AppSecurityManager {
    private List<String> auditLog;
    private File auditFile;

    public AppSecurityManager() {
        auditLog = new ArrayList<>();
        auditFile = new File(ChaiLiarMain.DATA_DIR + "/audit.log");
        loadAuditLog();
    }

    public void logAction(String action, User user) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String userInfo = (user == null) ? "SYSTEM" : user.getUsername() + " (Role: " + user.getRole() + ")";
        String logEntry = String.format("[%s] %s - %s", timestamp, userInfo, action);
        auditLog.add(logEntry);
        saveAuditLog();
    }

    public boolean checkPermission(User user, String action) {
        if (user == null) return false;
        boolean hasPermission = user.hasPermission(action);
        if (!hasPermission) logAction("DENIED: " + action + " (Insufficient permissions)", user);
        return hasPermission;
    }

    public List<String> getAuditLog() { return new ArrayList<>(auditLog); }

    private void saveAuditLog() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(auditFile, true))) {
            if (!auditLog.isEmpty()) pw.println(auditLog.get(auditLog.size() - 1));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void loadAuditLog() {
        if (!auditFile.exists()) return;
        try (Scanner sc = new Scanner(auditFile)) {
            while (sc.hasNextLine()) auditLog.add(sc.nextLine());
        } catch (Exception e) { e.printStackTrace(); }
    }
}

// ============================================================
// MENU ITEM (unchanged)
// ============================================================
class MenuItem implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private int price;
    private String category;
    private boolean available;
    private Date addedDate;
    private String addedBy;

    public MenuItem(String name, int price, String category, String addedBy) {
        this.name = name;
        this.price = price;
        this.category = category;
        this.available = true;
        this.addedDate = new Date();
        this.addedBy = addedBy;
    }

    public String getName() { return name; }
    public int getPrice() { return price; }
    public String getCategory() { return category; }
    public boolean isAvailable() { return available; }
    public Date getAddedDate() { return addedDate; }
    public String getAddedBy() { return addedBy; }

    public void setPrice(int price) { this.price = price; }
    public void setAvailable(boolean available) { this.available = available; }
    public void setName(String name) { this.name = name; }

    @Override
    public String toString() {
        return String.format("%s - Rs.%d (%s)", name, price, available ? "Available" : "Unavailable");
    }
}

// ============================================================
// HAPPY HOUR MANAGER (DB-based)
// ============================================================
class HappyHourManager {
    private LocalTime startTime;
    private LocalTime endTime;
    private int discountPercent;
    private boolean enabled;

    public HappyHourManager() {
        load();
    }

    public void setHappyHour(LocalTime start, LocalTime end, int discount) {
        this.startTime = start;
        this.endTime = end;
        this.discountPercent = Math.min(100, Math.max(0, discount));
        this.enabled = true;
        save();
    }

    public void disable() {
        this.enabled = false;
        save();
    }

    public boolean isHappyHour() {
        if (!enabled) return false;
        LocalTime now = LocalTime.now();
        return now.isAfter(startTime) && now.isBefore(endTime);
    }

    public int applyDiscount(int price) {
        if (!isHappyHour()) return price;
        int discounted = price * (100 - discountPercent) / 100;
        return Math.max(0, discounted);
    }

    public String getInfo() {
        if (!enabled) return "Happy Hour: Disabled";
        return String.format("Happy Hour: %s - %s (%d%% off)",
                startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                endTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                discountPercent);
    }

    public void save() {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement()) {
            stmt.execute("DELETE FROM happy_hour");
            String sql = String.format("INSERT INTO happy_hour VALUES ('%s', '%s', %d, %d)",
                    startTime == null ? "" : startTime.toString(),
                    endTime == null ? "" : endTime.toString(),
                    discountPercent, enabled ? 1 : 0);
            stmt.execute(sql);
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void load() {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM happy_hour")) {
            if (rs.next()) {
                String startStr = rs.getString("start");
                String endStr = rs.getString("end");
                if (!startStr.isEmpty()) startTime = LocalTime.parse(startStr);
                if (!endStr.isEmpty()) endTime = LocalTime.parse(endStr);
                discountPercent = rs.getInt("discount");
                enabled = rs.getInt("enabled") == 1;
            } else {
                enabled = false;
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }
}

// ============================================================
// SALES REPORT MANAGER (DB-based with archiving)
// ============================================================
class SalesReportManager {
    // In-memory caches for fast access (only last 90 days)
    private TreeMap<LocalDateTime, List<SaleTransaction>> salesByTime;
    private Map<String, List<SaleTransaction>> salesmanSales;
    private Map<String, List<SaleTransaction>> itemSales;
    private List<FreeItemLog> freeLogs;

    public SalesReportManager() {
        salesByTime = new TreeMap<>();
        salesmanSales = new HashMap<>();
        itemSales = new HashMap<>();
        freeLogs = new ArrayList<>();
        loadReports();
    }

    public void recordSale(String itemName, int originalPrice, int finalPrice, String salesmanId, User user, boolean isFree) {
        SaleTransaction transaction = new SaleTransaction(itemName, originalPrice, finalPrice, salesmanId, user.getUsername(), isFree);
        LocalDateTime now = LocalDateTime.now();

        // Insert into DB
        try (PreparedStatement pstmt = ChaiLiarMain.getDBConnection().prepareStatement(
                "INSERT INTO sales (item_name, original_price, final_price, salesman_id, sold_by, timestamp, is_free) VALUES (?,?,?,?,?,?,?)")) {
            pstmt.setString(1, itemName);
            pstmt.setInt(2, originalPrice);
            pstmt.setInt(3, finalPrice);
            pstmt.setString(4, salesmanId);
            pstmt.setString(5, user.getUsername());
            pstmt.setLong(6, now.toInstant(ZoneOffset.UTC).toEpochMilli());
            pstmt.setInt(7, isFree ? 1 : 0);
            pstmt.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }

        // Update in-memory cache (only if within last 90 days)
        if (now.isAfter(LocalDateTime.now().minusDays(90))) {
            salesByTime.computeIfAbsent(now, k -> new ArrayList<>()).add(transaction);
            salesmanSales.computeIfAbsent(salesmanId, k -> new ArrayList<>()).add(transaction);
            itemSales.computeIfAbsent(itemName, k -> new ArrayList<>()).add(transaction);
        }

        if (isFree) {
            freeLogs.add(new FreeItemLog(itemName, salesmanId, user.getUsername(), new Date()));
            // Also insert into free_logs table
            try (PreparedStatement pstmt = ChaiLiarMain.getDBConnection().prepareStatement(
                    "INSERT INTO free_logs (item_name, salesman_id, authorized_by, timestamp) VALUES (?,?,?,?)")) {
                pstmt.setString(1, itemName);
                pstmt.setString(2, salesmanId);
                pstmt.setString(3, user.getUsername());
                pstmt.setLong(4, System.currentTimeMillis());
                pstmt.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        }

        // Archive old data every 100 transactions (simple trigger)
        archiveOldSales();
    }

    private void archiveOldSales() {
        long cutoff = LocalDateTime.now().minusDays(90).toInstant(ZoneOffset.UTC).toEpochMilli();
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement()) {
            // Move old sales to archive table (if not exists)
            stmt.execute("CREATE TABLE IF NOT EXISTS sales_archive AS SELECT * FROM sales WHERE 1=0");
            stmt.execute("INSERT INTO sales_archive SELECT * FROM sales WHERE timestamp < " + cutoff);
            stmt.execute("DELETE FROM sales WHERE timestamp < " + cutoff);
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public boolean deleteLastSaleForSalesman(String salesmanId, String itemName, int finalPrice) {
        // Find the most recent sale for this salesman from DB
        try (PreparedStatement pstmt = ChaiLiarMain.getDBConnection().prepareStatement(
                "SELECT id FROM sales WHERE salesman_id = ? ORDER BY timestamp DESC LIMIT 1")) {
            pstmt.setString(1, salesmanId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int id = rs.getInt("id");
                try (PreparedStatement delStmt = ChaiLiarMain.getDBConnection().prepareStatement("DELETE FROM sales WHERE id = ?")) {
                    delStmt.setInt(1, id);
                    delStmt.executeUpdate();
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }

        // Also remove from caches (if present)
        // For simplicity, we reload caches (or you can implement fine removal)
        loadReports(); // Reload caches from DB (only last 90 days)
        return true;
    }

    public List<SaleTransaction> getSalesBySalesmanId(String salesmanId) {
        return salesmanSales.getOrDefault(salesmanId, new ArrayList<>());
    }

    public Map<String, Integer> getSalesmanTotal() {
        Map<String, Integer> totals = new HashMap<>();
        // Sum from caches (last 90 days)
        for (List<SaleTransaction> list : salesByTime.values()) {
            for (SaleTransaction t : list) {
                totals.put(t.getSalesmanId(), totals.getOrDefault(t.getSalesmanId(), 0) + t.getFinalPrice());
            }
        }
        // Also query DB for older? For simplicity, only last 90 days.
        return totals;
    }

    public List<FreeItemLog> getFreeLogs() { return new ArrayList<>(freeLogs); }

    public String generateFullReport() {
        // Use DB to generate full report (including archived)
        StringBuilder report = new StringBuilder();
        report.append(StringUtils.repeat("=", 60)).append("\n");
        report.append("COMPLETE SALES REPORT (including archived)\n");
        report.append("Generated: ").append(new Date()).append("\n");
        report.append(StringUtils.repeat("=", 60)).append("\n\n");

        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement()) {
            // Sales by salesman
            ResultSet rs = stmt.executeQuery(
                    "SELECT salesman_id, SUM(final_price) as total, COUNT(*) as cnt FROM sales GROUP BY salesman_id");
            report.append("SALES BY SALESMAN ID:\n");
            while (rs.next()) {
                report.append(String.format("%-20s: Rs.%,8d (%d sales)\n",
                        rs.getString("salesman_id"), rs.getInt("total"), rs.getInt("cnt")));
            }
            // Top items
            rs = stmt.executeQuery("SELECT item_name, COUNT(*) as cnt FROM sales GROUP BY item_name ORDER BY cnt DESC LIMIT 10");
            report.append("\nTOP SELLING ITEMS:\n");
            while (rs.next()) {
                report.append(String.format("%-20s: %d sales\n", rs.getString("item_name"), rs.getInt("cnt")));
            }
            // Totals
            rs = stmt.executeQuery("SELECT SUM(final_price) as total, COUNT(*) as cnt FROM sales");
            if (rs.next()) {
                report.append("\nTOTAL SALES SUMMARY:\n");
                report.append(String.format("Total Transactions: %d\n", rs.getInt("cnt")));
                report.append(String.format("Total Revenue: Rs.%,d\n", rs.getInt("total")));
                report.append(String.format("Average Sale Value: Rs.%,.2f\n",
                        rs.getInt("cnt") == 0 ? 0 : rs.getInt("total") / (double) rs.getInt("cnt")));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return report.toString();
    }

    public void exportToCSV() {
        String filename = ChaiLiarMain.DATA_DIR + "/sales_export_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename));
             Statement stmt = ChaiLiarMain.getDBConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sales ORDER BY timestamp")) {
            pw.println("Timestamp,Item,Original Price,Final Price,Salesman ID,Sold By,Free");
            while (rs.next()) {
                long ts = rs.getLong("timestamp");
                String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts));
                pw.printf("%s,%s,%d,%d,%s,%s,%d%n",
                        date, rs.getString("item_name"), rs.getInt("original_price"),
                        rs.getInt("final_price"), rs.getString("salesman_id"),
                        rs.getString("sold_by"), rs.getInt("is_free"));
            }
            JOptionPane.showMessageDialog(null, "Export completed: " + filename);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void saveReports() {
        // Nothing to do – DB is always saved
    }

    public void loadReports() {
        // Clear caches
        salesByTime.clear();
        salesmanSales.clear();
        itemSales.clear();
        freeLogs.clear();

        long cutoff = LocalDateTime.now().minusDays(90).toInstant(ZoneOffset.UTC).toEpochMilli();
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sales WHERE timestamp >= " + cutoff + " ORDER BY timestamp")) {
            while (rs.next()) {
                String item = rs.getString("item_name");
                int orig = rs.getInt("original_price");
                int finalPrice = rs.getInt("final_price");
                String salesmanId = rs.getString("salesman_id");
                String soldBy = rs.getString("sold_by");
                long ts = rs.getLong("timestamp");
                boolean isFree = rs.getInt("is_free") == 1;
                LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
                SaleTransaction t = new SaleTransaction(item, orig, finalPrice, salesmanId, soldBy, isFree);
                // Override timestamp field via reflection? Simpler: store timestamp in transaction? Not needed.
                salesByTime.computeIfAbsent(time, k -> new ArrayList<>()).add(t);
                salesmanSales.computeIfAbsent(salesmanId, k -> new ArrayList<>()).add(t);
                itemSales.computeIfAbsent(item, k -> new ArrayList<>()).add(t);
            }
        } catch (SQLException e) { e.printStackTrace(); }

        // Load free logs
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM free_logs")) {
            while (rs.next()) {
                freeLogs.add(new FreeItemLog(rs.getString("item_name"), rs.getString("salesman_id"),
                        rs.getString("authorized_by"), new Date(rs.getLong("timestamp"))));
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public NavigableMap<LocalDateTime, List<SaleTransaction>> getSalesByTimeRange(LocalDateTime from, boolean inclusive) {
        return salesByTime.tailMap(from, inclusive);
    }
}

// ============================================================
// SALE TRANSACTION (lightweight, no DB ID)
// ============================================================
class SaleTransaction {
    private String itemName;
    private int originalPrice;
    private int finalPrice;
    private String salesmanId;
    private String soldBy;
    private Date timestamp;
    private boolean isFree;

    public SaleTransaction(String itemName, int originalPrice, int finalPrice, String salesmanId, String soldBy, boolean isFree) {
        this.itemName = itemName;
        this.originalPrice = originalPrice;
        this.finalPrice = finalPrice;
        this.salesmanId = salesmanId;
        this.soldBy = soldBy;
        this.timestamp = new Date();
        this.isFree = isFree;
    }

    public String getItemName() { return itemName; }
    public int getOriginalPrice() { return originalPrice; }
    public int getFinalPrice() { return finalPrice; }
    public String getSalesmanId() { return salesmanId; }
    public String getSoldBy() { return soldBy; }
    public Date getTimestamp() { return timestamp; }
    public boolean isFree() { return isFree; }
}

// ============================================================
// FREE ITEM LOG
// ============================================================
class FreeItemLog {
    private String itemName;
    private String salesmanId;
    private String authorizedBy;
    private Date timestamp;

    public FreeItemLog(String itemName, String salesmanId, String authorizedBy, Date timestamp) {
        this.itemName = itemName;
        this.salesmanId = salesmanId;
        this.authorizedBy = authorizedBy;
        this.timestamp = timestamp;
    }

    public String getItemName() { return itemName; }
    public String getSalesmanId() { return salesmanId; }
    public String getAuthorizedBy() { return authorizedBy; }
    public Date getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return String.format("%s | Salesman: %s | Free: %s | Authorized by: %s",
                sdf.format(timestamp), salesmanId, itemName, authorizedBy);
    }
}

// ============================================================
// DASHBOARD PANEL (unchanged except using DB for persistence)
// ============================================================
class DashboardPanel extends JPanel {
    private JLabel salesLabel;
    private int totalSales;
    private int honestyScore;
    private PersistenceManager persistence;
    private SalesReportManager reportMgr;

    public DashboardPanel(PersistenceManager persistence, SalesReportManager reportMgr) {
        this.persistence = persistence;
        this.reportMgr = reportMgr;
        this.totalSales = 0;
        this.honestyScore = 100;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(20, 20, 20, 20));
        setBackground(new Color(230, 220, 200));
        setPreferredSize(new Dimension(280, 0));

        salesLabel = new JLabel("Total Sales: Rs. " + totalSales);
        salesLabel.setFont(new Font("SansSerif", Font.BOLD, 18));

        add(salesLabel);
        add(Box.createVerticalStrut(20));

        JButton reportBtn = new JButton("📊 View Full Report");
        reportBtn.addActionListener(e -> showFullReportWithWorker());
        add(reportBtn);
    }

    private void showFullReportWithWorker() {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                return reportMgr.generateFullReport();
            }
            @Override
            protected void done() {
                try {
                    String report = get();
                    JTextArea textArea = new JTextArea(report);
                    textArea.setEditable(false);
                    textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
                    JScrollPane scrollPane = new JScrollPane(textArea);
                    scrollPane.setPreferredSize(new Dimension(600, 500));
                    JOptionPane.showMessageDialog(DashboardPanel.this, scrollPane, "Complete Sales Report", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(DashboardPanel.this, "Error generating report", "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setCursor(Cursor.getDefaultCursor());
                }
            }
        };
        worker.execute();
    }

    public void addSale(int amount) {
        totalSales += amount;
        salesLabel.setText("Total Sales: Rs. " + totalSales);
        persistence.saveData(totalSales, honestyScore);
    }

    public void adjustHonesty(int points) {
        honestyScore = Math.max(0, Math.min(100, honestyScore + points));
        persistence.saveData(totalSales, honestyScore);
    }

    public int getTotalSales() { return totalSales; }
    public int getHonestyScore() { return honestyScore; }
}

// ============================================================
// PERSISTENCE MANAGER (DB-based for app state)
// ============================================================
class PersistenceManager {
    public void saveData(int sales, int honesty) {
        try (PreparedStatement pstmt = ChaiLiarMain.getDBConnection().prepareStatement(
                "INSERT OR REPLACE INTO app_state (key, int_value) VALUES (?, ?)")) {
            pstmt.setString(1, "total_sales");
            pstmt.setInt(2, sales);
            pstmt.executeUpdate();
            pstmt.setString(1, "honesty_score");
            pstmt.setInt(2, honesty);
            pstmt.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void loadData(DashboardPanel dashboard) {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT key, int_value FROM app_state")) {
            int sales = 0, honesty = 100;
            while (rs.next()) {
                if (rs.getString("key").equals("total_sales")) sales = rs.getInt("int_value");
                if (rs.getString("key").equals("honesty_score")) honesty = rs.getInt("int_value");
            }
            dashboard.addSale(sales);
            dashboard.adjustHonesty(honesty - dashboard.getHonestyScore());
        } catch (SQLException e) { e.printStackTrace(); }
    }
}

// ============================================================
// MENU PANEL (DB-based for menu items)
// ============================================================
class MenuPanel extends JPanel {
    private DashboardPanel dashboard;
    private TransactionLogPanel log;
    private AppSecurityManager securityManager;
    private SalesReportManager reportManager;
    private UserManager userManager;
    private HappyHourManager happyHour;
    private Map<String, MenuItem> menuItems;
    private JPanel menuGrid;
    private JComboBox<String> categoryFilter;
    private MenuTrie menuTrie;
    private InventoryPanel inventoryPanel;

    class MenuTrie {
        class TrieNode {
            Map<Character, TrieNode> children = new HashMap<>();
            boolean isEnd;
            MenuItem item;
        }
        private TrieNode root = new TrieNode();

        public void insert(MenuItem item) {
            TrieNode node = root;
            for (char c : item.getName().toLowerCase().toCharArray()) {
                node = node.children.computeIfAbsent(c, k -> new TrieNode());
            }
            node.isEnd = true;
            node.item = item;
        }

        public List<MenuItem> searchPrefix(String prefix) {
            List<MenuItem> results = new ArrayList<>();
            TrieNode node = root;
            for (char c : prefix.toLowerCase().toCharArray()) {
                node = node.children.get(c);
                if (node == null) return results;
            }
            collect(node, results);
            return results;
        }

        private void collect(TrieNode node, List<MenuItem> results) {
            if (node.isEnd) results.add(node.item);
            for (TrieNode child : node.children.values()) {
                collect(child, results);
            }
        }
    }

    public MenuPanel(DashboardPanel dashboard, TransactionLogPanel log,
                     AppSecurityManager securityManager, SalesReportManager reportManager,
                     UserManager userManager, HappyHourManager happyHour) {
        this.dashboard = dashboard;
        this.log = log;
        this.securityManager = securityManager;
        this.reportManager = reportManager;
        this.userManager = userManager;
        this.happyHour = happyHour;
        this.menuItems = new LinkedHashMap<>();
        this.menuTrie = new MenuTrie();

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("MENU"));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addItemBtn = new JButton("➕ Add Item");
        addItemBtn.addActionListener(e -> addMenuItem());
        JButton removeItemBtn = new JButton("❌ Remove Item");
        removeItemBtn.addActionListener(e -> removeMenuItem());
        JButton editItemBtn = new JButton("✏️ Edit Item");
        editItemBtn.addActionListener(e -> editMenuItem());
        JButton viewReportBtn = new JButton("📊 Item Report");
        viewReportBtn.addActionListener(e -> showItemReport());
        categoryFilter = new JComboBox<>(new String[]{"All", "Beverages", "Snacks", "Custom"});
        categoryFilter.addActionListener(e -> refreshMenuDisplay());

        JTextField searchField = new JTextField(10);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void changedUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void insertUpdate(DocumentEvent e) { filter(); }
            void filter() {
                String text = searchField.getText();
                if (text.isEmpty()) refreshMenuDisplay();
                else {
                    List<MenuItem> matches = menuTrie.searchPrefix(text);
                    refreshMenuDisplay(matches);
                }
            }
        });

        toolbar.add(addItemBtn);
        toolbar.add(removeItemBtn);
        toolbar.add(editItemBtn);
        toolbar.add(viewReportBtn);
        toolbar.add(new JLabel("Filter:"));
        toolbar.add(categoryFilter);
        toolbar.add(new JLabel("🔍 Search:"));
        toolbar.add(searchField);
        add(toolbar, BorderLayout.NORTH);

        menuGrid = new JPanel(new GridLayout(0, 2, 10, 10));
        menuGrid.setBorder(new EmptyBorder(10, 10, 10, 10));
        add(new JScrollPane(menuGrid), BorderLayout.CENTER);

        loadSavedMenuItems();
        if (menuItems.isEmpty()) {
            addDefaultItems();
        }
        refreshMenuDisplay();
    }

    public void setInventoryPanel(InventoryPanel inv) { this.inventoryPanel = inv; }

    private void addDefaultItems() {
        addMenuItemToMap(new MenuItem("Masala Chai", 25, "Beverages", "System"));
        addMenuItemToMap(new MenuItem("Ginger Chai", 30, "Beverages", "System"));
        addMenuItemToMap(new MenuItem("Elaichi Chai", 30, "Beverages", "System"));
        addMenuItemToMap(new MenuItem("Cold Coffee", 55, "Beverages", "System"));
        addMenuItemToMap(new MenuItem("Samosa", 15, "Snacks", "System"));
        addMenuItemToMap(new MenuItem("Biscuits", 10, "Snacks", "System"));
        addMenuItemToMap(new MenuItem("Khari", 12, "Snacks", "System"));
        addMenuItemToMap(new MenuItem("Cake Slice", 40, "Snacks", "System"));
    }

    private void addMenuItemToMap(MenuItem item) {
        menuItems.put(item.getName(), item);
        menuTrie.insert(item);
        saveMenuItems();
        refreshMenuDisplay();
    }

    private void addMenuItem() {
        User currentUser = userManager.getCurrentUser();
        if (!securityManager.checkPermission(currentUser, "MANAGE_MENU")) {
            JOptionPane.showMessageDialog(this,
                    "Only owners can add menu items!",
                    "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JTextField nameField = new JTextField();
        JTextField priceField = new JTextField();
        JComboBox<String> categoryCombo = new JComboBox<>(new String[]{"Beverages", "Snacks", "Custom"});
        JTextField customCategory = new JTextField();
        customCategory.setEnabled(false);
        categoryCombo.addActionListener(e -> customCategory.setEnabled(categoryCombo.getSelectedItem().equals("Custom")));
        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));
        panel.add(new JLabel("Item Name:")); panel.add(nameField);
        panel.add(new JLabel("Price (Rs.):")); panel.add(priceField);
        panel.add(new JLabel("Category:")); panel.add(categoryCombo);
        panel.add(new JLabel("Custom Category:")); panel.add(customCategory);
        int result = JOptionPane.showConfirmDialog(this, panel, "Add New Menu Item", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            try {
                String name = nameField.getText().trim();
                int price = Integer.parseInt(priceField.getText().trim());
                String category = categoryCombo.getSelectedItem().equals("Custom") ?
                        customCategory.getText().trim() : (String) categoryCombo.getSelectedItem();
                if (name.isEmpty() || price <= 0 || category.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Invalid input!", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                MenuItem newItem = new MenuItem(name, price, category, currentUser.getUsername());
                addMenuItemToMap(newItem);
                securityManager.logAction("Added menu item: " + name, currentUser);
                log.addEntry("Menu item added: " + name + " (Rs." + price + ") by " + currentUser.getUsername());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid price!", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void removeMenuItem() {
        User currentUser = userManager.getCurrentUser();
        if (!securityManager.checkPermission(currentUser, "MANAGE_MENU")) {
            JOptionPane.showMessageDialog(this,
                    "Only owners can remove menu items!",
                    "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String[] items = menuItems.keySet().toArray(new String[0]);
        if (items.length == 0) {
            JOptionPane.showMessageDialog(this, "No items to remove!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String selected = (String) JOptionPane.showInputDialog(this,
                "Select item to remove:", "Remove Menu Item",
                JOptionPane.QUESTION_MESSAGE, null, items, items[0]);
        if (selected != null) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to remove " + selected + "?",
                    "Confirm Removal", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                menuItems.remove(selected);
                menuTrie = new MenuTrie();
                for (MenuItem item : menuItems.values()) menuTrie.insert(item);
                saveMenuItems();
                refreshMenuDisplay();
                securityManager.logAction("Removed menu item: " + selected, currentUser);
                log.addEntry("Menu item removed: " + selected + " by " + currentUser.getUsername());
            }
        }
    }

    private void editMenuItem() {
        User currentUser = userManager.getCurrentUser();
        if (!securityManager.checkPermission(currentUser, "MANAGE_MENU")) {
            JOptionPane.showMessageDialog(this,
                    "Only owners can edit menu items!",
                    "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String[] items = menuItems.keySet().toArray(new String[0]);
        if (items.length == 0) {
            JOptionPane.showMessageDialog(this, "No items to edit!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String selected = (String) JOptionPane.showInputDialog(this,
                "Select item to edit:", "Edit Menu Item",
                JOptionPane.QUESTION_MESSAGE, null, items, items[0]);
        if (selected != null) {
            MenuItem item = menuItems.get(selected);
            JTextField nameField = new JTextField(item.getName());
            JTextField priceField = new JTextField(String.valueOf(item.getPrice()));
            JCheckBox availableCheck = new JCheckBox("Available", item.isAvailable());
            JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));
            panel.add(new JLabel("Item Name:")); panel.add(nameField);
            panel.add(new JLabel("Price (Rs.):")); panel.add(priceField);
            panel.add(new JLabel("Status:")); panel.add(availableCheck);
            int result = JOptionPane.showConfirmDialog(this, panel, "Edit Menu Item", JOptionPane.OK_CANCEL_OPTION);
            if (result == JOptionPane.OK_OPTION) {
                try {
                    String newName = nameField.getText().trim();
                    int newPrice = Integer.parseInt(priceField.getText().trim());
                    if (newName.isEmpty() || newPrice <= 0) {
                        JOptionPane.showMessageDialog(this, "Invalid input!", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    if (!newName.equals(selected)) menuItems.remove(selected);
                    item.setName(newName);
                    item.setPrice(newPrice);
                    item.setAvailable(availableCheck.isSelected());
                    menuItems.put(newName, item);
                    menuTrie = new MenuTrie();
                    for (MenuItem mi : menuItems.values()) menuTrie.insert(mi);
                    saveMenuItems();
                    refreshMenuDisplay();
                    securityManager.logAction("Edited menu item: " + selected + " -> " + newName, currentUser);
                    log.addEntry("Menu item edited: " + selected + " by " + currentUser.getUsername());
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Invalid price!", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void showItemReport() {
        Map<String, Integer> itemSales = new HashMap<>();
        for (MenuItem item : menuItems.values()) {
            itemSales.put(item.getName(), reportManager.getSalesByItem(item.getName()).size());
        }
        StringBuilder report = new StringBuilder();
        report.append("ITEM SALES REPORT\n");
        report.append(StringUtils.repeat("=", 50)).append("\n\n");
        for (Map.Entry<String, MenuItem> entry : menuItems.entrySet()) {
            MenuItem item = entry.getValue();
            int salesCount = itemSales.getOrDefault(item.getName(), 0);
            int revenue = salesCount * item.getPrice();
            report.append(String.format("%s\n", item.getName()));
            report.append(String.format("  Price: Rs.%d | Status: %s | Category: %s\n",
                    item.getPrice(), item.isAvailable() ? "Available" : "Unavailable", item.getCategory()));
            report.append(String.format("  Sales: %d units | Revenue: Rs.%,d\n", salesCount, revenue));
            report.append(String.format("  Added: %s by %s\n\n",
                    new SimpleDateFormat("dd-MM-yyyy").format(item.getAddedDate()), item.getAddedBy()));
        }
        JTextArea textArea = new JTextArea(report.toString());
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(500, 600));
        JOptionPane.showMessageDialog(this, scrollPane, "Item Report", JOptionPane.INFORMATION_MESSAGE);
    }

    private void refreshMenuDisplay(List<MenuItem> items) {
        menuGrid.removeAll();
        String filter = (String) categoryFilter.getSelectedItem();
        for (MenuItem item : items) {
            if (!filter.equals("All") && !item.getCategory().equals(filter) &&
                    !(filter.equals("Custom") && !item.getCategory().equals("Beverages") && !item.getCategory().equals("Snacks")))
                continue;
            if (item.isAvailable()) addMenuItemButton(item);
            else addUnavailableItemLabel(item);
        }
        menuGrid.revalidate();
        menuGrid.repaint();
    }

    private void refreshMenuDisplay() {
        refreshMenuDisplay(new ArrayList<>(menuItems.values()));
    }

    private void addMenuItemButton(MenuItem item) {
        JButton btn = new JButton("<html><b>" + item.getName() + "</b><br>" +
                "<font color='gray'>Rs. " + item.getPrice() + "</font><br>" +
                "<font size='2'>" + item.getCategory() + "</font></html>");
        btn.setFocusPainted(false);
        btn.addActionListener(e -> sellItem(item));
        menuGrid.add(btn);
    }

    private void addUnavailableItemLabel(MenuItem item) {
        JLabel label = new JLabel("<html><b>" + item.getName() + "</b><br>" +
                "<font color='gray'>Rs. " + item.getPrice() + "</font><br>" +
                "<font color='red'>(Unavailable)</font></html>");
        label.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setOpaque(true);
        label.setBackground(Color.LIGHT_GRAY);
        menuGrid.add(label);
    }

    private void sellItem(MenuItem item) {
        User currentUser = userManager.getCurrentUser();
        if (!securityManager.checkPermission(currentUser, "SELL")) {
            JOptionPane.showMessageDialog(this,
                    "You don't have permission to sell items!",
                    "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (inventoryPanel != null && !inventoryPanel.consumeItem(item.getName())) {
            JOptionPane.showMessageDialog(this, "Out of stock: " + item.getName(), "Stock Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String salesmanId;
        if (currentUser.getRole().equals("SALESMAN")) {
            salesmanId = currentUser.getSalesmanId();
        } else {
            String input = JOptionPane.showInputDialog(this, "Enter salesman ID (or name):");
            if (input == null || input.trim().isEmpty()) return;
            salesmanId = input.trim();
        }

        int finalPrice = happyHour.applyDiscount(item.getPrice());
        boolean isFree = (finalPrice == 0);
        dashboard.addSale(finalPrice);
        reportManager.recordSale(item.getName(), item.getPrice(), finalPrice, salesmanId, currentUser, isFree);
        securityManager.logAction("Sold: " + item.getName() + " for Rs." + finalPrice + (isFree ? " (FREE)" : ""), currentUser);
        log.addEntry("Sold: " + item.getName() + " for Rs." + finalPrice +
                " | Salesman ID: " + salesmanId + " | Sold by: " + currentUser.getUsername());

        ChaiLiarMain mainFrame = (ChaiLiarMain) SwingUtilities.getWindowAncestor(this);
        mainFrame.salesmanPanel.recordSaleById(salesmanId, finalPrice);
        mainFrame.salesmanPanel.sortBySales();

        Runnable undo = () -> {
            dashboard.addSale(-finalPrice);
            reportManager.deleteLastSaleForSalesman(salesmanId, item.getName(), finalPrice);
            log.addEntry("Undo sale of " + item.getName());
            if (inventoryPanel != null) inventoryPanel.addStock(item.getName(), 1);
        };
        mainFrame.pushUndo(undo);

        JOptionPane.showMessageDialog(this,
                "Sold " + item.getName() + " for Rs." + finalPrice + "!\n" +
                        (happyHour.isHappyHour() ? "Happy Hour discount applied!" : ""),
                "Sale Complete", JOptionPane.INFORMATION_MESSAGE);
    }

    public void loadSavedMenuItems() {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM menu_items")) {
            menuItems.clear();
            while (rs.next()) {
                String name = rs.getString("name");
                int price = rs.getInt("price");
                String category = rs.getString("category");
                boolean available = rs.getInt("available") == 1;
                Date addedDate = new Date(rs.getLong("added_date"));
                String addedBy = rs.getString("added_by");
                MenuItem item = new MenuItem(name, price, category, addedBy);
                item.setAvailable(available);
                // Override addedDate via reflection? For simplicity, not needed for UI.
                menuItems.put(name, item);
                menuTrie.insert(item);
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void saveMenuItems() {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement()) {
            stmt.execute("DELETE FROM menu_items");
            for (MenuItem item : menuItems.values()) {
                String sql = String.format("INSERT INTO menu_items VALUES ('%s', %d, '%s', %d, %d, '%s')",
                        item.getName(), item.getPrice(), item.getCategory(), item.isAvailable() ? 1 : 0,
                        item.getAddedDate().getTime(), item.getAddedBy());
                stmt.execute(sql);
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }
}

// ============================================================
// SALESMAN PANEL (unchanged, uses reportManager)
// ============================================================
class SalesmanPanel extends JPanel {
    private DefaultListModel<Salesman> salesmanModel;
    private JList<Salesman> salesmanList;
    private SalesReportManager reportManager;
    private AppSecurityManager securityManager;
    private UserManager userManager;
    private Map<String, LocalDateTime> lastShiftClose;
    private PriorityQueue<Salesman> topSalesmenHeap;
    private static final int TOP_N = 5;
    private LeaderboardPanel leaderboardPanel;

    public SalesmanPanel(SalesReportManager reportManager, AppSecurityManager securityManager,
                         UserManager userManager, LeaderboardPanel leaderboardPanel) {
        this.reportManager = reportManager;
        this.securityManager = securityManager;
        this.userManager = userManager;
        this.leaderboardPanel = leaderboardPanel;
        this.lastShiftClose = new HashMap<>();
        this.topSalesmenHeap = new PriorityQueue<>((a, b) -> Integer.compare(b.totalSales, a.totalSales));

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Salesmen Tracker"));

        salesmanModel = new DefaultListModel<>();
        salesmanList = new JList<>(salesmanModel);
        salesmanList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        add(new JScrollPane(salesmanList), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton addBtn = new JButton("Add Salesman");
        JButton auditBtn = new JButton("View Audit Log");
        JButton freeLogBtn = new JButton("Free Item Log");
        JButton topBtn = new JButton("🏆 Top Salesmen");
        JButton reportBtn = new JButton("Show Report");
        JButton shiftBtn = new JButton("Close Shift");

        addBtn.addActionListener(e -> addSalesman());
        auditBtn.addActionListener(e -> showAuditLog());
        freeLogBtn.addActionListener(e -> showFreeLog());
        topBtn.addActionListener(e -> showTopSalesmen());
        reportBtn.addActionListener(e -> showSalesReport());
        shiftBtn.addActionListener(e -> closeShift());

        User current = userManager.getCurrentUser();
        boolean isOwner = (current != null && current.getRole().equals("OWNER"));
        if (isOwner) {
            buttonPanel.add(addBtn);
            buttonPanel.add(auditBtn);
            buttonPanel.add(freeLogBtn);
            buttonPanel.add(topBtn);
        }
        buttonPanel.add(reportBtn);
        buttonPanel.add(shiftBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void addSalesman() {
        String name = JOptionPane.showInputDialog(this, "Enter Salesman Name:");
        if (name != null && !name.trim().isEmpty()) {
            Salesman newSalesman = new Salesman(name);
            salesmanModel.addElement(newSalesman);
            rebuildHeap();
            updateLeaderboard();
        }
    }

    public void recordSaleById(String salesmanId, int amount) {
        for (int i = 0; i < salesmanModel.size(); i++) {
            Salesman s = salesmanModel.get(i);
            if (s.id.equals(salesmanId)) {
                s.addSale(amount);
                rebuildHeap();
                salesmanList.repaint();
                updateLeaderboard();
                return;
            }
        }
        Salesman newSalesman = new Salesman("Unknown", salesmanId);
        newSalesman.addSale(amount);
        salesmanModel.addElement(newSalesman);
        rebuildHeap();
        updateLeaderboard();
    }

    public void sortBySales() {
        List<Salesman> list = new ArrayList<>();
        for (int i = 0; i < salesmanModel.size(); i++) list.add(salesmanModel.get(i));
        list.sort((a, b) -> Integer.compare(b.totalSales, a.totalSales));
        salesmanModel.clear();
        for (Salesman s : list) salesmanModel.addElement(s);
        rebuildHeap();
        updateLeaderboard();
    }

    private void rebuildHeap() {
        topSalesmenHeap.clear();
        for (int i = 0; i < salesmanModel.size(); i++) {
            topSalesmenHeap.offer(salesmanModel.get(i));
        }
    }

    private void updateLeaderboard() {
        List<Salesman> list = new ArrayList<>();
        for (int i = 0; i < salesmanModel.size(); i++) list.add(salesmanModel.get(i));
        leaderboardPanel.updateLeaderboard(list);
    }

    private void showTopSalesmen() {
        StringBuilder sb = new StringBuilder("🏆 Top " + TOP_N + " Salesmen\n\n");
        PriorityQueue<Salesman> temp = new PriorityQueue<>(topSalesmenHeap);
        for (int i = 0; i < TOP_N && !temp.isEmpty(); i++) {
            Salesman s = temp.poll();
            sb.append(s.name).append(" (ID: ").append(s.id.substring(0,8)).append(")\n");
            sb.append("   Total Sales: Rs.").append(s.totalSales).append("\n\n");
        }
        JTextArea ta = new JTextArea(sb.toString());
        ta.setEditable(false);
        JOptionPane.showMessageDialog(this, ta, "Leaderboard", JOptionPane.INFORMATION_MESSAGE);
    }

    private void closeShift() {
        User current = userManager.getCurrentUser();
        if (current == null) return;
        String salesmanId;
        if (current.getRole().equals("SALESMAN")) {
            salesmanId = current.getSalesmanId();
        } else {
            String input = JOptionPane.showInputDialog(this, "Enter salesman ID for shift summary:");
            if (input == null || input.trim().isEmpty()) return;
            salesmanId = input.trim();
        }

        LocalDateTime since = lastShiftClose.getOrDefault(salesmanId, LocalDateTime.MIN);
        NavigableMap<LocalDateTime, List<SaleTransaction>> after = reportManager.getSalesByTimeRange(since, true);
        List<SaleTransaction> shiftSales = new ArrayList<>();
        for (List<SaleTransaction> list : after.values()) {
            for (SaleTransaction t : list) {
                if (t.getSalesmanId().equals(salesmanId)) shiftSales.add(t);
            }
        }

        int totalRevenue = shiftSales.stream().mapToInt(SaleTransaction::getFinalPrice).sum();
        long itemCount = shiftSales.size();
        long freeCount = shiftSales.stream().filter(SaleTransaction::isFree).count();

        StringBuilder summary = new StringBuilder();
        summary.append("Shift Summary for Salesman ").append(salesmanId).append("\n");
        summary.append("-----------------------------------\n");
        summary.append("Period: ").append(since == LocalDateTime.MIN ? "Beginning" : since.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" to now\n");
        summary.append("Total Revenue: Rs. ").append(totalRevenue).append("\n");
        summary.append("Total Items Sold: ").append(itemCount).append("\n");
        summary.append("Free Items Given: ").append(freeCount).append("\n");

        JOptionPane.showMessageDialog(this, summary.toString(), "Shift Summary", JOptionPane.INFORMATION_MESSAGE);
        lastShiftClose.put(salesmanId, LocalDateTime.now());
    }

    private void showFreeLog() {
        List<FreeItemLog> logs = reportManager.getFreeLogs();
        StringBuilder sb = new StringBuilder("FREE ITEM LOG\n");
        sb.append(StringUtils.repeat("=", 60)).append("\n\n");
        for (FreeItemLog log : logs) sb.append(log.toString()).append("\n");
        JTextArea textArea = new JTextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(800, 500));
        JOptionPane.showMessageDialog(this, scrollPane, "Free Item Audit Log", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showSalesReport() {
        Map<String, Integer> salesmanTotals = reportManager.getSalesmanTotal();
        StringBuilder report = new StringBuilder("SALESMAN PERFORMANCE REPORT\n");
        report.append(StringUtils.repeat("=", 50)).append("\n\n");
        for (int i = 0; i < salesmanModel.size(); i++) {
            Salesman s = salesmanModel.get(i);
            int totalSales = salesmanTotals.getOrDefault(s.id, 0);
            int numberOfSales = reportManager.getSalesBySalesmanId(s.id).size();
            report.append(String.format("%s (ID: %s)\n", s.name, s.id));
            report.append(String.format("  Total Revenue: Rs.%,d\n", totalSales));
            report.append(String.format("  Number of Sales: %d\n", numberOfSales));
            report.append(String.format("  Average Sale: Rs.%,.2f\n",
                    numberOfSales == 0 ? 0 : totalSales / (double) numberOfSales));
            report.append("\n");
        }
        JTextArea textArea = new JTextArea(report.toString());
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(500, 400));
        JOptionPane.showMessageDialog(this, scrollPane, "Salesman Report",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showAuditLog() {
        List<String> auditLog = securityManager.getAuditLog();
        StringBuilder logText = new StringBuilder("AUDIT LOG\n");
        logText.append(StringUtils.repeat("=", 50)).append("\n\n");
        for (String entry : auditLog) logText.append(entry).append("\n");
        JTextArea textArea = new JTextArea(logText.toString());
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(800, 500));
        JOptionPane.showMessageDialog(this, scrollPane, "Audit Log",
                JOptionPane.INFORMATION_MESSAGE);
    }
}

// ============================================================
// SALESMAN CLASS
// ============================================================
class Salesman {
    String id;
    String name;
    int totalSales;

    public Salesman(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.totalSales = 0;
    }

    public Salesman(String name, String id) {
        this.name = name;
        this.id = id;
        this.totalSales = 0;
    }

    public void addSale(int amount) {
        totalSales += amount;
    }

    @Override
    public String toString() {
        return String.format("%-20s → Rs.%,6d", name, totalSales);
    }
}

// ============================================================
// INVENTORY PANEL (DB-based)
// ============================================================
class InventoryPanel extends JPanel {
    private Map<String, Integer> stock;
    private DefaultListModel<String> stockModel;
    private JList<String> stockList;
    private MenuPanel menuPanel;
    private TransactionLogPanel log;
    private Timer lowStockToastTimer;

    public InventoryPanel(MenuPanel menuPanel, TransactionLogPanel log) {
        this.menuPanel = menuPanel;
        this.log = log;
        this.stock = new HashMap<>();
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Inventory Management"));
        setPreferredSize(new Dimension(0, 200));
        stockModel = new DefaultListModel<>();
        stockList = new JList<>(stockModel);
        stockList.setFont(new Font("Monospaced", Font.PLAIN, 11));
        loadInventoryFromDB();
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton restockBtn = new JButton("Restock Selected");
        restockBtn.addActionListener(e -> restockItem());
        JButton bulkRestockBtn = new JButton("Bulk Restock");
        bulkRestockBtn.addActionListener(e -> bulkRestock());
        JButton lowStockBtn = new JButton("Show Low Stock");
        lowStockBtn.addActionListener(e -> showLowStock());
        buttonPanel.add(restockBtn);
        buttonPanel.add(bulkRestockBtn);
        buttonPanel.add(lowStockBtn);
        add(new JScrollPane(stockList), BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        updateDisplay();

        menuPanel.setInventoryPanel(this);

        lowStockToastTimer = new Timer(30000, e -> checkLowStockAndToast());
        lowStockToastTimer.start();
    }

    private void loadInventoryFromDB() {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM inventory")) {
            stock.clear();
            while (rs.next()) {
                stock.put(rs.getString("item_name"), rs.getInt("quantity"));
            }
            // If empty, initialize defaults
            if (stock.isEmpty()) {
                stock.put("Masala Chai", 50);
                stock.put("Ginger Chai", 45);
                stock.put("Elaichi Chai", 40);
                stock.put("Cold Coffee", 30);
                stock.put("Samosa", 60);
                stock.put("Biscuits", 100);
                stock.put("Khari", 80);
                stock.put("Cake Slice", 25);
                saveInventoryToDB();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void saveInventoryToDB() {
        try (Statement stmt = ChaiLiarMain.getDBConnection().createStatement()) {
            stmt.execute("DELETE FROM inventory");
            for (Map.Entry<String, Integer> e : stock.entrySet()) {
                stmt.execute("INSERT INTO inventory VALUES ('" + e.getKey() + "', " + e.getValue() + ")");
            }
        } catch (SQLException ex) { ex.printStackTrace(); }
    }

    private void updateDisplay() {
        stockModel.clear();
        for (Map.Entry<String, Integer> entry : stock.entrySet()) {
            String status = entry.getValue() < 10 ? "⚠️ LOW! " :
                    (entry.getValue() < 20 ? "⚠️ " : "✓ ");
            stockModel.addElement(String.format("%s%-20s: %3d units",
                    status, entry.getKey(), entry.getValue()));
        }
    }

    private void checkLowStockAndToast() {
        List<String> lowItems = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : stock.entrySet()) {
            if (entry.getValue() < 10) lowItems.add(entry.getKey() + " (" + entry.getValue() + ")");
        }
        if (!lowItems.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Low stock alert: " + String.join(", ", lowItems),
                    "Inventory Alert", JOptionPane.WARNING_MESSAGE);
            log.addEntry("Low stock alert: " + String.join(", ", lowItems));
        }
    }

    public boolean consumeItem(String itemName) {
        if (stock.containsKey(itemName) && stock.get(itemName) > 0) {
            stock.put(itemName, stock.get(itemName) - 1);
            saveInventoryToDB();
            updateDisplay();
            return true;
        }
        return false;
    }

    public void addStock(String itemName, int qty) {
        stock.put(itemName, stock.getOrDefault(itemName, 0) + qty);
        saveInventoryToDB();
        updateDisplay();
    }

    private void restockItem() {
        String selected = stockList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Please select an item to restock!",
                    "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String itemName = extractItemName(selected);
        String quantityStr = JOptionPane.showInputDialog(this,
                "Enter quantity to add for " + itemName + ":", "10");
        try {
            int quantity = Integer.parseInt(quantityStr);
            if (quantity > 0) {
                stock.put(itemName, stock.get(itemName) + quantity);
                saveInventoryToDB();
                updateDisplay();
                log.addEntry("Restocked " + itemName + " with " + quantity + " units");
                JOptionPane.showMessageDialog(this,
                        "Restocked " + quantity + " units of " + itemName,
                        "Restock Complete", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid quantity!", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String extractItemName(String displayString) {
        String name = displayString.substring(displayString.indexOf("✓") + 2);
        if (name.contains("LOW!")) name = name.substring(5);
        return name.substring(0, name.indexOf(":")).trim();
    }

    private void bulkRestock() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));
        Map<String, JTextField> fields = new HashMap<>();
        for (String item : stock.keySet()) {
            panel.add(new JLabel(item + ":"));
            JTextField field = new JTextField("10", 5);
            fields.put(item, field);
            panel.add(field);
        }
        int result = JOptionPane.showConfirmDialog(this, panel, "Bulk Restock",
                JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
                try {
                    int quantity = Integer.parseInt(entry.getValue().getText());
                    if (quantity > 0) {
                        stock.put(entry.getKey(), stock.get(entry.getKey()) + quantity);
                    }
                } catch (NumberFormatException ex) { /* skip */ }
            }
            saveInventoryToDB();
            updateDisplay();
            log.addEntry("Bulk restock completed");
            JOptionPane.showMessageDialog(this, "Bulk restock completed!",
                    "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void showLowStock() {
        StringBuilder sb = new StringBuilder("LOW STOCK ITEMS (< 20 units):\n");
        sb.append(StringUtils.repeat("=", 40)).append("\n\n");
        boolean hasLowStock = false;
        for (Map.Entry<String, Integer> entry : stock.entrySet()) {
            if (entry.getValue() < 20) {
                sb.append(String.format("%-20s: %d units\n", entry.getKey(), entry.getValue()));
                hasLowStock = true;
            }
        }
        if (!hasLowStock) sb.append("All items have sufficient stock (20+ units)!");
        JTextArea textArea = new JTextArea(sb.toString());
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(400, 300));
        JOptionPane.showMessageDialog(this, scrollPane, "Low Stock Alert",
                JOptionPane.WARNING_MESSAGE);
    }
}

// ============================================================
// ORDER PANEL (with Undo stack)
// ============================================================
class OrderPanel extends JPanel {
    private DefaultListModel<String> orderModel;
    private TransactionLogPanel log;
    private Deque<Runnable> undoStack;

    public OrderPanel(TransactionLogPanel log) {
        this.log = log;
        this.undoStack = new ArrayDeque<>();
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(300, 0));
        setBorder(BorderFactory.createTitledBorder("Active Orders"));
        orderModel = new DefaultListModel<>();
        JList<String> orderList = new JList<>(orderModel);
        orderList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        add(new JScrollPane(orderList), BorderLayout.CENTER);
        JPanel buttonPanel = new JPanel();
        JButton addOrderBtn = new JButton("Add Sample Order");
        addOrderBtn.addActionListener(e -> {
            String order = JOptionPane.showInputDialog(this, "Enter order details:");
            if (order != null && !order.trim().isEmpty()) addOrder(order);
        });
        JButton clearBtn = new JButton("Clear Completed Orders");
        clearBtn.addActionListener(e -> {
            orderModel.clear();
            log.addEntry("Cleared all orders.");
        });
        JButton undoBtn = new JButton("↩️ Undo Last Sale");
        undoBtn.addActionListener(e -> {
            if (!undoStack.isEmpty()) {
                Runnable undo = undoStack.pop();
                undo.run();
                log.addEntry("Undid last sale");
            } else {
                JOptionPane.showMessageDialog(this, "Nothing to undo");
            }
        });
        buttonPanel.add(addOrderBtn);
        buttonPanel.add(clearBtn);
        buttonPanel.add(undoBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    public void addOrder(String order) {
        orderModel.addElement(order);
        log.addEntry("New Order: " + order);
    }

    public void pushUndo(Runnable undoAction) {
        undoStack.push(undoAction);
        if (undoStack.size() > 50) undoStack.removeLast();
    }
}

// ============================================================
// HONESTY PANEL (unchanged)
// ============================================================
class HonestyPanel extends JPanel {
    private DashboardPanel dashboard;
    private TransactionLogPanel log;
    private UserManager userManager;
    private SalesReportManager reportManager;
    private AppSecurityManager securityManager;
    private JProgressBar honestyBar;
    private JLabel statusLabel;
    private InventoryPanel inventory;

    public HonestyPanel(DashboardPanel dashboard, TransactionLogPanel log,
                        UserManager userManager, SalesReportManager reportManager,
                        AppSecurityManager securityManager, InventoryPanel inventory) {
        this.dashboard = dashboard;
        this.log = log;
        this.userManager = userManager;
        this.reportManager = reportManager;
        this.securityManager = securityManager;
        this.inventory = inventory;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Honesty Meter & Actions"));

        JPanel meterPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        meterPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        honestyBar = new JProgressBar(0, 100);
        honestyBar.setValue(dashboard.getHonestyScore());
        honestyBar.setStringPainted(true);
        statusLabel = new JLabel("Status: Honest Chaiwala");
        statusLabel.setFont(new Font("SansSerif", Font.ITALIC, 14));
        updateStatusLabel();
        meterPanel.add(honestyBar);
        meterPanel.add(statusLabel);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        addHonestyButton(buttonPanel, "Correct Change (+10)", 10, true);
        addHonestyButton(buttonPanel, "Admitted Old Stock (+15)", 15, true);
        addHonestyButton(buttonPanel, "Fake 'Organic' Label (-20)", -20, false);
        addHonestyButton(buttonPanel, "Secret Spice Lie (-10)", -10, false);
        addHonestyButton(buttonPanel, "Refund Customer (+5)", 5, true);
        addHonestyButton(buttonPanel, "Overcharge (-15)", -15, false);
        JButton freeTeaBtn = new JButton("Award Free Tea");
        freeTeaBtn.addActionListener(e -> awardFreeTea());
        freeTeaBtn.setForeground(Color.BLUE);
        buttonPanel.add(freeTeaBtn);

        add(meterPanel, BorderLayout.NORTH);
        add(buttonPanel, BorderLayout.CENTER);
    }

    private void updateStatusLabel() {
        int score = dashboard.getHonestyScore();
        if (score >= 70) {
            statusLabel.setText("Status: Honest Chaiwala");
            honestyBar.setForeground(new Color(34, 139, 34));
        } else if (score >= 40) {
            statusLabel.setText("Status: Somewhat Shady");
            honestyBar.setForeground(Color.ORANGE);
        } else {
            statusLabel.setText("Status: Certified Liar!");
            honestyBar.setForeground(Color.RED);
        }
        honestyBar.setValue(score);
    }

    private void addHonestyButton(JPanel panel, String label, int points, boolean isTruth) {
        JButton btn = new JButton(label);
        btn.setForeground(isTruth ? new Color(0, 100, 0) : Color.RED);
        btn.addActionListener(e -> {
            dashboard.adjustHonesty(points);
            updateStatusLabel();
            log.addEntry("Honesty Action: " + label + " (" + (points > 0 ? "+" : "") + points + ")");
        });
        panel.add(btn);
    }

    private void awardFreeTea() {
        User current = userManager.getCurrentUser();
        if (!securityManager.checkPermission(current, "SELL")) {
            JOptionPane.showMessageDialog(this,
                    "You don't have permission to award free tea!",
                    "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String itemName = JOptionPane.showInputDialog(this, "Enter item name for free tea:");
        if (itemName == null || itemName.trim().isEmpty()) return;

        if (!inventory.consumeItem(itemName)) {
            JOptionPane.showMessageDialog(this, "Item not available or out of stock!", "Stock Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String salesmanId;
        if (current.getRole().equals("SALESMAN")) {
            salesmanId = current.getSalesmanId();
        } else {
            salesmanId = JOptionPane.showInputDialog(this, "Enter salesman ID:");
            if (salesmanId == null || salesmanId.trim().isEmpty()) return;
        }
        reportManager.recordSale(itemName, 0, 0, salesmanId, current, true);
        securityManager.logAction("Awarded free tea: " + itemName, current);
        log.addEntry("Free tea awarded: " + itemName + " to salesman " + salesmanId + " by " + current.getUsername());
        JOptionPane.showMessageDialog(this, "Free tea recorded!", "Free Item", JOptionPane.INFORMATION_MESSAGE);
    }
}

// ============================================================
// LEADERBOARD PANEL
// ============================================================
class LeaderboardPanel extends JPanel {
    private DefaultListModel<String> leaderboardModel;
    private JList<String> leaderboardList;

    public LeaderboardPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Chaiwala Leaderboard"));
        leaderboardModel = new DefaultListModel<>();
        leaderboardList = new JList<>(leaderboardModel);
        add(new JScrollPane(leaderboardList), BorderLayout.CENTER);
    }

    public void updateLeaderboard(List<Salesman> salesmen) {
        leaderboardModel.clear();
        salesmen.stream()
                .sorted((a, b) -> Integer.compare(b.totalSales, a.totalSales))
                .limit(10)
                .forEach(s -> leaderboardModel.addElement(s.name + " → Rs." + s.totalSales));
    }
}

// ============================================================
// THEME MANAGER (unchanged)
// ============================================================
class ThemeManager {
    private JFrame frame;
    private boolean isDark = false;

    public ThemeManager(JFrame frame) {
        this.frame = frame;
        JMenuBar menuBar = new JMenuBar();
        JMenu settingsMenu = new JMenu("Settings");
        JMenuItem toggleTheme = new JMenuItem("Toggle Theme");
        toggleTheme.addActionListener(e -> toggleTheme());
        settingsMenu.add(toggleTheme);
        JMenuItem happyHourItem = new JMenuItem("Set Happy Hour");
        happyHourItem.addActionListener(e -> showHappyHourDialog());
        settingsMenu.add(happyHourItem);
        JMenuItem backupItem = new JMenuItem("Manual Backup");
        backupItem.addActionListener(e -> ((ChaiLiarMain) frame).manualBackup());
        settingsMenu.add(backupItem);
        JMenuItem exportItem = new JMenuItem("Export Sales to CSV");
        exportItem.addActionListener(e -> {
            SalesReportManager mgr = ((ChaiLiarMain) frame).reportManager;
            mgr.exportToCSV();
        });
        settingsMenu.add(exportItem);
        JMenuItem logoutItem = new JMenuItem("Logout");
        logoutItem.addActionListener(e -> ((ChaiLiarMain) frame).logout());
        settingsMenu.add(logoutItem);
        menuBar.add(settingsMenu);
        frame.setJMenuBar(menuBar);
    }

    private void showHappyHourDialog() {
        HappyHourManager hh = ((ChaiLiarMain) frame).happyHour;
        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));
        JTextField startField = new JTextField(5);
        JTextField endField = new JTextField(5);
        JTextField discountField = new JTextField(3);
        JCheckBox enableCheck = new JCheckBox("Enable Happy Hour");
        panel.add(new JLabel("Start (HH:MM):")); panel.add(startField);
        panel.add(new JLabel("End (HH:MM):")); panel.add(endField);
        panel.add(new JLabel("Discount %:")); panel.add(discountField);
        panel.add(enableCheck);
        int result = JOptionPane.showConfirmDialog(frame, panel, "Happy Hour Settings",
                JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            try {
                LocalTime start = LocalTime.parse(startField.getText());
                LocalTime end = LocalTime.parse(endField.getText());
                int discount = Integer.parseInt(discountField.getText());
                if (enableCheck.isSelected()) hh.setHappyHour(start, end, discount);
                else hh.disable();
                JOptionPane.showMessageDialog(frame, hh.getInfo());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Invalid input! Use HH:MM format and integer discount.",
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void applyLightTheme() {
        frame.getContentPane().setBackground(new Color(245, 245, 220));
        isDark = false;
    }

    public void applyDarkTheme() {
        frame.getContentPane().setBackground(new Color(60, 63, 65));
        isDark = true;
    }

    public void toggleTheme() {
        if (isDark) applyLightTheme();
        else applyDarkTheme();
    }
}

// ============================================================
// TRANSACTION LOG PANEL (unchanged)
// ============================================================
class TransactionLogPanel extends JPanel {
    private DefaultListModel<String> logModel;

    public TransactionLogPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Transaction Log"));
        setPreferredSize(new Dimension(0, 120));
        logModel = new DefaultListModel<>();
        JList<String> logList = new JList<>(logModel);
        logList.setFont(new Font("Monospaced", Font.PLAIN, 10));
        add(new JScrollPane(logList), BorderLayout.CENTER);
    }

    public void addEntry(String entry) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logModel.addElement("[" + timestamp + "] " + entry);
    }
}

// ============================================================
// FEEDBACK SYSTEM (unchanged)
// ============================================================
class FeedbackSystem {
    private javax.swing.Timer timer;

    public void attachToDashboard(DashboardPanel dashboard, TransactionLogPanel log) {
        timer = new javax.swing.Timer(20000, e -> {
            Random rand = new Random();
            int mood = rand.nextInt(100);
            if (mood < 40) {
                dashboard.adjustHonesty(-5);
                log.addEntry("👎 Customer complained: Tea too sweet!");
            } else if (mood > 70) {
                dashboard.adjustHonesty(+5);
                log.addEntry("👍 Customer praised: Best chai ever!");
            } else {
                log.addEntry("😐 Customer was neutral.");
            }
        });
        timer.start();
    }

    public void stopFeedback() {
        if (timer != null) timer.stop();
    }
}

// ============================================================
// UTILITY STRING REPEAT
// ============================================================
class StringUtils {
    public static String repeat(String str, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder(str.length() * count);
        for (int i = 0; i < count; i++) sb.append(str);
        return sb.toString();
    }
}
