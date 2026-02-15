import javafx.animation.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image; 
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.prefs.Preferences;
// updated to texteditor.java with deepseek ai mode
public class texteditor extends Application {

  private TextArea textArea;
  private Stage primaryStage;
  private Timeline autoSaveTimeline;
  private Timeline typingDelayTimeline;
  private File currentFile;
  private VBox lineNumbersVBox;
  private boolean textChanged = false;
  private boolean isTyping = false;
  private static final String RECOVERY_FILE_PATH = System.getProperty("user.home") + File.separator + ".texteditor_recovery.txt";
  private static final String RECOVERY_INFO_PATH = System.getProperty("user.home") + File.separator + ".texteditor_recovery_info.txt";
  private static final String LAST_FILE_KEY = "lastOpenedFile";
  private static final String BACKGROUND_IMAGE_NAME = "Background.png";
  private static final String FXML_FILE_NAME = "texteditor.fxml";
  private static final String BACKGROUND_FOLDER = "texteditor_assets";
  private static final String CUSTOM_ASSETS_PATH = getCustomAssetsPath(); // Can be set via configureAssetsPaths()
  private static final String LOG_FILE_PATH = getLogFilePath();
  private Preferences prefs;
  
  /**
   * Gets the log file path using the same method as assets path
   */
  private static String getLogFilePath() {
    String assetsPath = getCustomAssetsPath();
    if (assetsPath != null) {
      return assetsPath + File.separator + "nbheditor.log";
    }
    return System.getProperty("user.home") + File.separator + "Downloads" + File.separator + "nbheditor" + File.separator + "nbheditor.log";
  }
  
  @FXML private AnchorPane contentPane;
  @FXML private MenuItem saveMenuItem;
  @FXML private MenuItem openMenuItem;
  @FXML private MenuItem deleteMenuItem;
  @FXML private MenuItem quitMenuItem;
  
  private MenuBar menuBar; // Reference to menu bar for theming
  
  // Edit menu items
  @FXML private MenuItem undoMenuItem;
  @FXML private MenuItem redoMenuItem;
  @FXML private MenuItem cutMenuItem;
  @FXML private MenuItem copyMenuItem;
  @FXML private MenuItem pasteMenuItem;
  @FXML private MenuItem selectAllMenuItem;
  
  // Theme menu items
  @FXML private MenuItem lightThemeMenuItem;
  @FXML private MenuItem darkThemeMenuItem;
  
  // Features menu items
  @FXML private MenuItem recentFilesMenuItem;
  
  private Stage splashStage;
  private boolean isDarkTheme = false;
  private ProgressBar autoSaveProgressBar;
  private Label autoSaveLabel;
  private HBox autoSaveContainer;
  
  /**
   * Configure the assets path before launching the application
   * To change the assets path, modify this method
   */
  private static void configureAssetsPaths() {
    // To use a custom assets path, uncomment and modify the following line with a relative path:
     System.setProperty("texteditor.assets.path.relative", "Downloads/nbheditor");
    //
    // The app will automatically add the user's home directory to create the full path
    // It will look for Background.png and texteditor.fxml in this directory
  }
  
  /**
   * Gets the custom assets path, handling both absolute and relative paths
   * @return The full path to the assets directory or null if not set
   */
  private static String getCustomAssetsPath() {
    // Check for absolute path first
    String absolutePath = System.getProperty("texteditor.assets.path");
    if (absolutePath != null) {
      return absolutePath;
    }
    
    // Check for relative path
    String relativePath = System.getProperty("texteditor.assets.path.relative");
    if (relativePath != null) {
      // Get user home directory
      String userHome = System.getProperty("user.home");
      // Combine with relative path
      return userHome + File.separator + relativePath;
    }
    
    return null;
  }
  
  public static void main(String[] args) {
    configureAssetsPaths();
    launch(args);
  }

  @Override
  public void start(Stage primaryStage) {
    // Show splash screen
    showSplashScreen();
    
    // Initialize the main application in a background thread
    Task<Void> initTask = new Task<Void>() {
      @Override
      protected Void call() throws Exception {
        // Simulate initialization time
        Thread.sleep(2000);
        return null;
      }
    };
    
    initTask.setOnSucceeded(event -> {
      // Close splash screen and show main application
      if (splashStage != null) {
        splashStage.close();
      }
      initializeMainApplication(primaryStage);
    });
    
    new Thread(initTask).start();
  }
  
  private void showSplashScreen() {
    try {
      splashStage = new Stage(StageStyle.UNDECORATED);
      
      // Load the background image - try multiple possible locations
      InputStream imageStream = findBackgroundImage();
      if (imageStream == null) {
        throw new IOException("Background image not found");
      }
      
      Image backgroundImage = new Image(imageStream);
      ImageView imageView = new ImageView(backgroundImage);
      
      // Create a splash screen with the image
      StackPane splashLayout = new StackPane();
      splashLayout.getChildren().add(imageView);
      
      Scene splashScene = new Scene(splashLayout);
      splashStage.setScene(splashScene);
      
      // Center on screen
      Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
      splashStage.setX((screenBounds.getWidth() - backgroundImage.getWidth()) / 2);
      splashStage.setY((screenBounds.getHeight() - backgroundImage.getHeight()) / 2);
      
      splashStage.show();
    } catch (Exception e) {
      e.printStackTrace();
      // If splash screen fails, proceed directly to main application
      initializeMainApplication(new Stage());
    }
  }
  
  private void initializeMainApplication(Stage primaryStage) {
    // Set the primary stage
    this.primaryStage = primaryStage;
    
    // Initialize preferences
    prefs = Preferences.userNodeForPackage(texteditor.class);
    
    // Handle application close request
    primaryStage.setOnCloseRequest(event -> {
      // Check if there are unsaved changes
      if (textChanged) {
        event.consume(); // Prevent the window from closing immediately
        
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("You have unsaved changes");
        alert.setContentText("Would you like to save before closing?");
        
        ButtonType saveButton = new ButtonType("Save");
        ButtonType dontSaveButton = new ButtonType("Don't Save");
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        
        alert.getButtonTypes().setAll(saveButton, dontSaveButton, cancelButton);
        
        alert.showAndWait().ifPresent(buttonType -> {
          if (buttonType == saveButton) {
            // Save and then exit
            saveFile();
            saveRecoveryFile();
            stopAutoSave();
            Platform.exit();
          } else if (buttonType == dontSaveButton) {
            // Exit without saving
            saveRecoveryFile();
            stopAutoSave();
            Platform.exit();
          }
          // If cancel, do nothing and return to the editor
        });
      } else {
        // No unsaved changes, proceed with normal close
        saveRecoveryFile();
        stopAutoSave();
      }
    });
    try {
      // Load FXML file from custom location or fallback to resources
      FXMLLoader loader = new FXMLLoader();
      loader.setController(this);
      
      // Try to load FXML from custom path first
      Parent root = loadFXMLFromCustomPath(loader);
      if (root == null) {
        // Fallback to standard resource path
        loader = new FXMLLoader(getClass().getResource("/" + FXML_FILE_NAME));
        loader.setController(this);
        root = loader.load();
      }
      
      // Create a BorderPane for the editor layout
      BorderPane editorLayout = new BorderPane();
      
      // Initialize the text area
      textArea = new TextArea();
      textArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; -fx-font-size: 13px;");
      textArea.setWrapText(true);
      
      // Create line numbers
      lineNumbersVBox = new VBox();
      lineNumbersVBox.setAlignment(Pos.TOP_RIGHT);
      lineNumbersVBox.setPadding(new Insets(5, 8, 0, 0));
      lineNumbersVBox.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 0 1 0 0;");
      lineNumbersVBox.setPrefWidth(45);
      
      // Create a HBox to hold line numbers and text area
      HBox contentBox = new HBox();
      contentBox.getChildren().addAll(lineNumbersVBox, textArea);
      HBox.setHgrow(textArea, javafx.scene.layout.Priority.ALWAYS);
      
      // Create auto-save progress bar
      createAutoSaveProgressBar();
      
      // Create VBox to hold content and progress bar
      VBox editorWithProgress = new VBox();
      editorWithProgress.getChildren().addAll(contentBox, autoSaveContainer);
      VBox.setVgrow(contentBox, javafx.scene.layout.Priority.ALWAYS);
      
      // Set the editor with progress as the center of the BorderPane
      editorLayout.setCenter(editorWithProgress);
      
      // Fill the entire content pane
      AnchorPane.setTopAnchor(editorLayout, 0.0);
      AnchorPane.setBottomAnchor(editorLayout, 0.0);
      AnchorPane.setLeftAnchor(editorLayout, 0.0);
      AnchorPane.setRightAnchor(editorLayout, 0.0);
      
      // Add the editor layout to the content pane
      contentPane.getChildren().add(editorLayout);
      
      // Get reference to menu bar for theming
      if (root instanceof BorderPane) {
        BorderPane borderPane = (BorderPane) root;
        if (borderPane.getTop() instanceof MenuBar) {
          menuBar = (MenuBar) borderPane.getTop();
        }
      }
      
      // Set up line number tracking
      setupLineNumbers();
      
      // Set up menu item actions
      setupMenuActions();
      
      // Set up auto-save functionality
      setupAutoSave();
      
      // Detect and apply system theme
      detectAndApplySystemTheme();
      
      // Check for recovery file
      checkForRecovery();
      
      // Get screen dimensions and set window to fullscreen
      Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
      double screenWidth = screenBounds.getWidth();
      double screenHeight = screenBounds.getHeight();
      
      Scene scene = new Scene(root, screenWidth, screenHeight);
      primaryStage.setScene(scene);
      primaryStage.setTitle("Text Editor");
      
      // Set window to fullscreen
      primaryStage.setMaximized(true);
      
      primaryStage.show();
      
      // Ensure the main window is focused after splash screen closes
      primaryStage.toFront();
      
    } catch (IOException e) {
      Alert alert = new Alert(Alert.AlertType.ERROR);
      alert.setTitle("Error");
      alert.setHeaderText("Failed to load FXML");
      alert.setContentText("Error: " + e.getMessage());
      alert.showAndWait();
      e.printStackTrace();
    }
  }
  
  private void setupMenuActions() {
    // File menu actions
    saveMenuItem.setOnAction(event -> saveFile());
    openMenuItem.setOnAction(event -> openFile());
    deleteMenuItem.setOnAction(event -> deleteFileWithAnimation());
    quitMenuItem.setOnAction(event -> {
      // Check for unsaved changes before quitting
      if (textChanged) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("You have unsaved changes");
        alert.setContentText("Would you like to save before closing?");
        
        ButtonType saveButton = new ButtonType("Save");
        ButtonType dontSaveButton = new ButtonType("Don't Save");
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        
        alert.getButtonTypes().setAll(saveButton, dontSaveButton, cancelButton);
        
        alert.showAndWait().ifPresent(buttonType -> {
          if (buttonType == saveButton) {
            // Save and then exit
            saveFile();
            saveRecoveryFile();
            stopAutoSave();
            Platform.exit();
          } else if (buttonType == dontSaveButton) {
            // Exit without saving
            saveRecoveryFile();
            stopAutoSave();
            Platform.exit();
          }
          // If cancel, do nothing and return to the editor
        });
      } else {
        // No unsaved changes, proceed with normal quit
        stopAutoSave();
        Platform.exit();
      }
    });
    
    // Edit menu actions
    setupEditMenuActions();
    
    // Theme menu actions
    setupThemeMenuActions();
    
    // Features menu actions
    setupFeaturesMenuActions();
  }
  
  /**
   * Sets up the Edit menu actions
   */
  private void setupEditMenuActions() {
    // Undo action
    undoMenuItem.setOnAction(event -> textArea.undo());
    
    // Redo action
    redoMenuItem.setOnAction(event -> textArea.redo());
    
    // Cut action
    cutMenuItem.setOnAction(event -> textArea.cut());
    
    // Copy action
    copyMenuItem.setOnAction(event -> textArea.copy());
    
    // Paste action
    pasteMenuItem.setOnAction(event -> textArea.paste());
    
    // Select All action
    selectAllMenuItem.setOnAction(event -> textArea.selectAll());
    
    // Update Edit menu item states based on text selection
    textArea.selectionProperty().addListener((observable, oldValue, newValue) -> {
      boolean hasSelection = !textArea.getSelectedText().isEmpty();
      cutMenuItem.setDisable(!hasSelection);
      copyMenuItem.setDisable(!hasSelection);
    });
    
    // Initially disable cut/copy if no selection
    cutMenuItem.setDisable(true);
    copyMenuItem.setDisable(true);
  }
  
  /**
   * Sets up the Theme menu actions
   */
  private void setupThemeMenuActions() {
    // Light theme action
    lightThemeMenuItem.setOnAction(event -> {
      isDarkTheme = false;
      applyLightTheme();
    });
    
    // Dark theme action
    darkThemeMenuItem.setOnAction(event -> {
      isDarkTheme = true;
      applyDarkTheme();
    });
  }
  
  /**
   * Sets up the Features menu actions
   */
  private void setupFeaturesMenuActions() {
    // Check if Features menu exists in FXML, if not create it programmatically
    if (recentFilesMenuItem == null) {
      createFeaturesMenuProgrammatically();
    } else {
      // Recent files action
      recentFilesMenuItem.setOnAction(event -> openRecentFile());
    }
  }
  
  /**
   * Creates Features menu programmatically if not found in FXML
   */
  private void createFeaturesMenuProgrammatically() {
    if (menuBar != null) {
      // Create Features menu
      Menu featuresMenu = new Menu("Features");
      
      // Create Recent Files menu item
      MenuItem recentFiles = new MenuItem("Recent Files");
      recentFiles.setOnAction(event -> openRecentFile());
      
      // Add to Features menu
      featuresMenu.getItems().add(recentFiles);
      
      // Add test progress bar item (commented out for production)
      // MenuItem testProgress = new MenuItem("Test Auto-Save Progress");
      // testProgress.setOnAction(event -> {
      //   System.out.println("Testing progress bar...");
      //   showAutoSaveProgress();
      // });
      // featuresMenu.getItems().add(testProgress);
      
      // Add Features menu to menu bar
      menuBar.getMenus().add(featuresMenu);
      
      System.out.println("Features menu created programmatically");
    }
  }
  
  /**
   * Opens the most recent file from log
   */
  private void openRecentFile() {
    String recentFilePath = getLastFileFromLog();
    
    if (recentFilePath != null && !recentFilePath.trim().isEmpty()) {
      File recentFile = new File(recentFilePath.trim());
      if (recentFile.exists()) {
        // Clear the log file after reading
        clearLogFile();
        openFileFromPath(recentFile);
      } else {
        showError("File Not Found", "Recent file not found", "The file " + recentFilePath + " no longer exists.");
        clearLogFile(); // Clear invalid entry
      }
    } else {
      showError("No Recent Files", "No recent files found", "No recent files available to open.");
    }
  }
  
  /**
   * Logs file access to the log file (overwrites previous entry)
   */
  private void logFileAccess(String filePath) {
    try {
      // Ensure log directory exists
      File logFile = new File(LOG_FILE_PATH);
      File logDir = logFile.getParentFile();
      if (!logDir.exists()) {
        logDir.mkdirs();
      }
      
      // Overwrite log file with only the current file path
      FileWriter fw = new FileWriter(logFile, false); // false = overwrite
      BufferedWriter bw = new BufferedWriter(fw);
      bw.write(filePath);
      bw.flush();
      bw.close();
    } catch (IOException e) {
      System.err.println("Failed to log file access: " + e.getMessage());
    }
  }
  
  /**
   * Gets the file path from log
   */
  private String getLastFileFromLog() {
    File logFile = new File(LOG_FILE_PATH);
    
    if (!logFile.exists()) {
      return null;
    }
    
    try {
      BufferedReader br = new BufferedReader(new FileReader(logFile));
      String filePath = br.readLine(); // Read only the first line
      br.close();
      return filePath;
    } catch (IOException e) {
      System.err.println("Failed to read log file: " + e.getMessage());
      return null;
    }
  }
  
  /**
   * Clears the log file
   */
  private void clearLogFile() {
    try {
      File logFile = new File(LOG_FILE_PATH);
      if (logFile.exists()) {
        FileWriter fw = new FileWriter(logFile, false);
        fw.write("");
        fw.close();
      }
    } catch (IOException e) {
      System.err.println("Failed to clear log file: " + e.getMessage());
    }
  }
  
  /**
   * Detects the system theme and applies it automatically
   */
  private void detectAndApplySystemTheme() {
    boolean systemIsDark = isSystemDarkTheme();
    isDarkTheme = systemIsDark;
    
    if (systemIsDark) {
      applyDarkTheme();
    } else {
      applyLightTheme();
    }
  }
  
  /**
   * Detects if the system is using dark theme (Linux only)
   * @return true if system is using dark theme, false otherwise
   */
  private boolean isSystemDarkTheme() {
    try {
      return detectLinuxDarkTheme();
    } catch (Exception e) {
      System.err.println("Failed to detect system theme: " + e.getMessage());
    }
    
    // Default to light theme if detection fails
    return false;
  }
  
  /**
   * Detects dark theme on Linux systems across multiple desktop environments
   */
  private boolean detectLinuxDarkTheme() {
    String desktop = System.getenv("XDG_CURRENT_DESKTOP");
    if (desktop == null) desktop = System.getenv("DESKTOP_SESSION");
    
    try {
      // KDE Plasma
      if (desktop != null && desktop.toLowerCase().contains("kde")) {
        return detectKDEDarkTheme();
      }
      
      // XFCE
      if (desktop != null && desktop.toLowerCase().contains("xfce")) {
        return detectXFCEDarkTheme();
      }
      
      // MATE
      if (desktop != null && desktop.toLowerCase().contains("mate")) {
        return detectMATEDarkTheme();
      }
      
      // Cinnamon
      if (desktop != null && desktop.toLowerCase().contains("cinnamon")) {
        return detectCinnamonDarkTheme();
      }
      
      // GNOME (including Ubuntu)
      if (desktop == null || desktop.toLowerCase().contains("gnome") || desktop.toLowerCase().contains("ubuntu")) {
        return detectGNOMEDarkTheme();
      }
      
    } catch (Exception e) {
      System.err.println("Desktop-specific detection failed: " + e.getMessage());
    }
    
    // Universal fallbacks
    return detectUniversalLinuxDarkTheme();
  }
  
  private boolean detectKDEDarkTheme() throws Exception {
    // Check KDE color scheme
    Process process = Runtime.getRuntime().exec(new String[]{"kreadconfig5", "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme"});
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    String colorScheme = reader.readLine();
    reader.close();
    
    if (colorScheme != null && colorScheme.toLowerCase().contains("dark")) {
      return true;
    }
    
    // Check plasma theme
    process = Runtime.getRuntime().exec(new String[]{"kreadconfig5", "--file", "plasmarc", "--group", "Theme", "--key", "name"});
    reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    String theme = reader.readLine();
    reader.close();
    
    return theme != null && theme.toLowerCase().contains("dark");
  }
  
  private boolean detectXFCEDarkTheme() throws Exception {
    Process process = Runtime.getRuntime().exec(new String[]{"xfconf-query", "-c", "xsettings", "-p", "/Net/ThemeName"});
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    String theme = reader.readLine();
    reader.close();
    
    return theme != null && theme.toLowerCase().contains("dark");
  }
  
  private boolean detectMATEDarkTheme() throws Exception {
    Process process = Runtime.getRuntime().exec(new String[]{"gsettings", "get", "org.mate.interface", "gtk-theme"});
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    String theme = reader.readLine();
    reader.close();
    
    return theme != null && theme.toLowerCase().contains("dark");
  }
  
  private boolean detectCinnamonDarkTheme() throws Exception {
    Process process = Runtime.getRuntime().exec(new String[]{"gsettings", "get", "org.cinnamon.desktop.interface", "gtk-theme"});
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    String theme = reader.readLine();
    reader.close();
    
    return theme != null && theme.toLowerCase().contains("dark");
  }
  
  private boolean detectGNOMEDarkTheme() throws Exception {
    // Check GNOME color scheme preference (newer versions)
    try {
      Process process = Runtime.getRuntime().exec(new String[]{"gsettings", "get", "org.gnome.desktop.interface", "color-scheme"});
      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String colorScheme = reader.readLine();
      reader.close();
      
      if (colorScheme != null && colorScheme.contains("dark")) {
        return true;
      }
    } catch (Exception e) {
      // Ignore and try next method
    }
    
    // Check GNOME theme
    Process process = Runtime.getRuntime().exec(new String[]{"gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"});
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    String theme = reader.readLine();
    reader.close();
    
    return theme != null && theme.toLowerCase().contains("dark");
  }
  
  private boolean detectUniversalLinuxDarkTheme() {
    try {
      // Check GTK theme environment variable
      String gtkTheme = System.getenv("GTK_THEME");
      if (gtkTheme != null && gtkTheme.toLowerCase().contains("dark")) {
        return true;
      }
      
      // Check GTK settings file
      String home = System.getProperty("user.home");
      File gtkSettings = new File(home + "/.config/gtk-3.0/settings.ini");
      if (gtkSettings.exists()) {
        BufferedReader reader = new BufferedReader(new FileReader(gtkSettings));
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.contains("gtk-theme-name") && line.toLowerCase().contains("dark")) {
            reader.close();
            return true;
          }
        }
        reader.close();
      }
      
      // Check Qt theme for Qt-based DEs
      String qtStyle = System.getenv("QT_STYLE_OVERRIDE");
      if (qtStyle != null && qtStyle.toLowerCase().contains("dark")) {
        return true;
      }
      
    } catch (Exception e) {
      System.err.println("Universal Linux theme detection failed: " + e.getMessage());
    }
    
    return false;
  }
  

  
  /**
   * Applies light theme to the application
   */
  private void applyLightTheme() {
    // Apply CSS for light theme
    if (primaryStage != null && primaryStage.getScene() != null) {
      Scene scene = primaryStage.getScene();
      scene.getStylesheets().clear();
      
      // Add inline CSS for light theme
      String lightCSS = 
        ".menu-bar { -fx-background-color: #f0f0f0; } " +
        ".menu { -fx-text-fill: black; } " +
        ".menu-item { -fx-text-fill: black; -fx-background-color: #f0f0f0; } " +
        ".menu-item:focused { -fx-background-color: #e0e0e0; } " +
        ".context-menu { -fx-background-color: #f0f0f0; }";
      
      scene.getRoot().setStyle("-fx-base: #ffffff; -fx-background: #ffffff; " + lightCSS);
    }
    
    // Text area light theme
    if (textArea != null) {
      textArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; " +
                       "-fx-font-size: 14px; " +
                       "-fx-control-inner-background: #ffffff; " +
                       "-fx-text-fill: #000000;");
    }
    
    // Line numbers light theme
    if (lineNumbersVBox != null) {
      lineNumbersVBox.setStyle("-fx-background-color: #f8f8f8; " +
                              "-fx-border-color: #ddd; " +
                              "-fx-border-width: 0 1 0 0;");
      
      // Update line number labels
      lineNumbersVBox.getChildren().forEach(node -> {
        if (node instanceof Label) {
          ((Label) node).setTextFill(Color.web("#666666"));
        }
      });
    }
    
    // Content pane light theme
    if (contentPane != null) {
      contentPane.setStyle("-fx-background-color: #ffffff;");
    }
  }
  
  /**
   * Applies dark theme to the application
   */
  private void applyDarkTheme() {
    // Apply CSS for dark theme
    if (primaryStage != null && primaryStage.getScene() != null) {
      Scene scene = primaryStage.getScene();
      scene.getStylesheets().clear();
      
      // Add inline CSS for dark theme
      String darkCSS = 
        ".menu-bar { -fx-background-color: #3c3c3c; } " +
        ".menu { -fx-text-fill: white; } " +
        ".menu-item { -fx-text-fill: white; -fx-background-color: #3c3c3c; } " +
        ".menu-item:focused { -fx-background-color: #555555; } " +
        ".context-menu { -fx-background-color: #3c3c3c; }";
      
      scene.getRoot().setStyle("-fx-base: #2b2b2b; -fx-background: #2b2b2b; " + darkCSS);
    }
    
    // Text area dark theme
    if (textArea != null) {
      textArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; " +
                       "-fx-font-size: 14px; " +
                       "-fx-control-inner-background: #1e1e1e; " +
                       "-fx-text-fill: #ffffff;");
    }
    
    // Line numbers dark theme
    if (lineNumbersVBox != null) {
      lineNumbersVBox.setStyle("-fx-background-color: #2b2b2b; " +
                              "-fx-border-color: #555; " +
                              "-fx-border-width: 0 1 0 0;");
      
      // Update line number labels
      lineNumbersVBox.getChildren().forEach(node -> {
        if (node instanceof Label) {
          ((Label) node).setTextFill(Color.web("#888888"));
        }
      });
    }
    
    // Content pane dark theme
    if (contentPane != null) {
      contentPane.setStyle("-fx-background-color: #2b2b2b;");
    }
  }
  
  private void setupLineNumbers() {
    // Initial line numbers
    updateLineNumbers();
    
    // Add listener for text changes
    textArea.textProperty().addListener((observable, oldValue, newValue) -> {
      updateLineNumbers();
      if (!oldValue.equals(newValue)) {
        textChanged = true;
        isTyping = true;
        System.out.println("Text changed - triggering auto-save delay. Current file: " + (currentFile != null ? currentFile.getName() : "null"));
        triggerAutoSaveDelay();
      }
    });
    
    // Add listener for scrolling to sync line numbers with text
    textArea.scrollTopProperty().addListener((observable, oldValue, newValue) -> {
      lineNumbersVBox.setTranslateY(-newValue.doubleValue());
    });
  }
  
  private void updateLineNumbers() {
    lineNumbersVBox.getChildren().clear();
    
    String text = textArea.getText();
    if (text.isEmpty()) {
      // Add at least one line number
      Label label = createLineNumberLabel(1);
      lineNumbersVBox.getChildren().add(label);
      return;
    }
    
    String[] lines = text.split("\\R", -1);
    int lineCount = lines.length;
    
    for (int i = 0; i < lineCount; i++) {
      Label label = createLineNumberLabel(i + 1);
      lineNumbersVBox.getChildren().add(label);
    }
  }
  
  private Label createLineNumberLabel(int lineNumber) {
    Label label = new Label(String.valueOf(lineNumber));
    label.setPrefWidth(35);
    label.setAlignment(Pos.CENTER_RIGHT);
    label.setFont(Font.font("Consolas, Monaco, monospace", 13));
    label.setTextFill(isDarkTheme ? Color.web("#888888") : Color.web("#666666"));
    return label;
  }
  
  private void setupAutoSave() {
    // Create typing delay timeline - triggers auto-save when user stops typing
    typingDelayTimeline = new Timeline(new KeyFrame(Duration.seconds(2), event -> {
      if (textChanged && !isTyping) {
        performAutoSave();
      }
    }));
    
    // Create periodic recovery save timeline (every 10 seconds)
    autoSaveTimeline = new Timeline(new KeyFrame(Duration.seconds(10), event -> {
      saveRecoveryFile(); // Always save recovery file periodically
    }));
    autoSaveTimeline.setCycleCount(Timeline.INDEFINITE);
    autoSaveTimeline.play();
  }
  
  /**
   * Performs the actual auto-save operation
   */
  private void performAutoSave() {
    System.out.println("performAutoSave called - textChanged: " + textChanged + ", currentFile: " + (currentFile != null ? currentFile.getName() : "null"));
    
    if (textChanged) {
      showAutoSaveProgress();
      
      if (currentFile != null) {
        System.out.println("Auto-saving file: " + currentFile.getName());
        saveToFile(currentFile);
      } else {
        System.out.println("Auto-saving recovery file only");
        saveRecoveryFile();
      }
      textChanged = false;
    }
  }
  
  /**
   * Triggers auto-save after user stops typing
   */
  private void triggerAutoSaveDelay() {
    System.out.println("triggerAutoSaveDelay called");
    if (typingDelayTimeline != null) {
      typingDelayTimeline.stop();
    }
    isTyping = false;
    typingDelayTimeline.play();
  }
  
  /**
   * Creates the auto-save progress bar
   */
  private void createAutoSaveProgressBar() {
    autoSaveProgressBar = new ProgressBar(0);
    autoSaveProgressBar.setMaxWidth(Double.MAX_VALUE);
    autoSaveProgressBar.setPrefHeight(8);
    autoSaveProgressBar.setStyle("-fx-accent: #4CAF50;");
    
    autoSaveLabel = new Label("Auto-saving...");
    autoSaveLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666; -fx-font-weight: bold;");
    
    autoSaveContainer = new HBox(10);
    autoSaveContainer.setAlignment(Pos.CENTER_LEFT);
    autoSaveContainer.setPadding(new Insets(6, 12, 6, 12));
    autoSaveContainer.getChildren().addAll(autoSaveLabel, autoSaveProgressBar);
    
    HBox.setHgrow(autoSaveProgressBar, Priority.ALWAYS);
    
    autoSaveContainer.setVisible(false);
    autoSaveContainer.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
    
    System.out.println("Auto-save progress bar created");
  }
  
  /**
   * Shows animated auto-save progress
   */
  private void showAutoSaveProgress() {
    Platform.runLater(() -> {
      if (autoSaveContainer == null) {
        System.out.println("AutoSave container is null!");
        return;
      }
      
      System.out.println("Showing auto-save progress animation");
      autoSaveContainer.setVisible(true);
      autoSaveContainer.setOpacity(1);
      autoSaveProgressBar.setProgress(0);
      
      // Animate progress bar
      Timeline progressTimeline = new Timeline(
        new KeyFrame(Duration.ZERO, new KeyValue(autoSaveProgressBar.progressProperty(), 0)),
        new KeyFrame(Duration.millis(800), new KeyValue(autoSaveProgressBar.progressProperty(), 1))
      );
      
      progressTimeline.setOnFinished(e -> {
        System.out.println("Progress animation completed");
        // Show completion briefly then hide
        PauseTransition pause = new PauseTransition(Duration.millis(1000));
        pause.setOnFinished(event -> {
          FadeTransition fadeOut = new FadeTransition(Duration.millis(500), autoSaveContainer);
          fadeOut.setToValue(0);
          fadeOut.setOnFinished(fadeEvent -> {
            autoSaveContainer.setVisible(false);
            autoSaveContainer.setOpacity(1); // Reset opacity
            System.out.println("Progress bar hidden");
          });
          fadeOut.play();
        });
        pause.play();
      });
      
      progressTimeline.play();
    });
  }
  
  private void stopAutoSave() {
    if (autoSaveTimeline != null) {
      autoSaveTimeline.stop();
    }
    if (typingDelayTimeline != null) {
      typingDelayTimeline.stop();
    }
  }
  
  private void saveFile() {
    if (currentFile == null) {
      saveFileAs();
    } else {
      saveToFileWithAnimation(currentFile);
    }
  }
  
  private void saveFileAs() {
    FileChooser fileChooser = new FileChooser();
    File file = fileChooser.showSaveDialog(primaryStage);
    if (file != null) {
      currentFile = file;
      saveToFileWithAnimation(file);
    }
  }
  
  private void saveToFileWithAnimation(File file) {
    // Show saving animation
    showSavingAnimation();
    
    // Perform save operation in background
    Task<Boolean> saveTask = new Task<Boolean>() {
      @Override
      protected Boolean call() throws Exception {
        Thread.sleep(500); // Simulate save time
        return performSave(file);
      }
    };
    
    saveTask.setOnSucceeded(event -> {
      boolean success = saveTask.getValue();
      if (success) {
        showSaveSuccessAnimation();
      } else {
        showSaveErrorAnimation();
      }
    });
    
    new Thread(saveTask).start();
  }
  
  private boolean performSave(File file) {
    try {
      // Show manual save progress
      Platform.runLater(() -> showManualSaveProgress());
      
      FileWriter fw = new FileWriter(file);
      BufferedWriter bw = new BufferedWriter(fw);
      bw.write(textArea.getText());
      bw.flush();
      bw.close();
      
      Platform.runLater(() -> {
        primaryStage.setTitle("Text Editor - " + file.getName());
        textChanged = false;
        prefs.put(LAST_FILE_KEY, file.getAbsolutePath());
        logFileAccess(file.getAbsolutePath());
      });
      
      return true;
    } catch (IOException e) {
      Platform.runLater(() -> showError("Save Error", "Can't save your file", e.getMessage()));
      return false;
    }
  }
  
  /**
   * Shows progress for manual save operations
   */
  private void showManualSaveProgress() {
    autoSaveLabel.setText("Saving...");
    autoSaveContainer.setVisible(true);
    autoSaveProgressBar.setProgress(0);
    
    Timeline progressTimeline = new Timeline(
      new KeyFrame(Duration.ZERO, new KeyValue(autoSaveProgressBar.progressProperty(), 0)),
      new KeyFrame(Duration.millis(500), new KeyValue(autoSaveProgressBar.progressProperty(), 1))
    );
    
    progressTimeline.setOnFinished(e -> {
      autoSaveLabel.setText("Auto-saving..."); // Reset label
      PauseTransition pause = new PauseTransition(Duration.millis(300));
      pause.setOnFinished(event -> {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(300), autoSaveContainer);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(fadeEvent -> {
          autoSaveContainer.setVisible(false);
          autoSaveContainer.setOpacity(1);
        });
        fadeOut.play();
      });
      pause.play();
    });
    
    progressTimeline.play();
  }
  
  private void showSavingAnimation() {
    Label savingLabel = new Label("Saving...");
    savingLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: #2196F3; -fx-background-color: rgba(255,255,255,0.9); -fx-padding: 10px; -fx-background-radius: 5px;");
    
    StackPane overlay = new StackPane(savingLabel);
    overlay.setStyle("-fx-background-color: rgba(0,0,0,0.3);");
    
    contentPane.getChildren().add(overlay);
    AnchorPane.setTopAnchor(overlay, 0.0);
    AnchorPane.setBottomAnchor(overlay, 0.0);
    AnchorPane.setLeftAnchor(overlay, 0.0);
    AnchorPane.setRightAnchor(overlay, 0.0);
    
    // Fade in animation
    FadeTransition fadeIn = new FadeTransition(Duration.millis(200), overlay);
    fadeIn.setFromValue(0.0);
    fadeIn.setToValue(1.0);
    fadeIn.play();
    
    // Rotate animation for saving text
    RotateTransition rotate = new RotateTransition(Duration.millis(1000), savingLabel);
    rotate.setByAngle(360);
    rotate.setCycleCount(Timeline.INDEFINITE);
    rotate.play();
  }
  
  private void showSaveSuccessAnimation() {
    // Remove saving overlay
    contentPane.getChildren().removeIf(node -> node instanceof StackPane && ((StackPane) node).getChildren().get(0) instanceof Label);
    
    // Create success notification
    Label successLabel = new Label("✓ File Saved Successfully!");
    successLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: white; -fx-background-color: #4CAF50; -fx-padding: 15px 25px; -fx-background-radius: 25px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);");
    
    StackPane notification = new StackPane(successLabel);
    notification.setAlignment(Pos.TOP_CENTER);
    notification.setPadding(new Insets(50, 0, 0, 0));
    notification.setMouseTransparent(true);
    
    contentPane.getChildren().add(notification);
    AnchorPane.setTopAnchor(notification, 0.0);
    AnchorPane.setLeftAnchor(notification, 0.0);
    AnchorPane.setRightAnchor(notification, 0.0);
    
    // Slide down animation
    TranslateTransition slideDown = new TranslateTransition(Duration.millis(400), notification);
    slideDown.setFromY(-100);
    slideDown.setToY(0);
    slideDown.setInterpolator(Interpolator.EASE_OUT);
    
    // Scale animation
    ScaleTransition scale = new ScaleTransition(Duration.millis(300), successLabel);
    scale.setFromX(0.8);
    scale.setFromY(0.8);
    scale.setToX(1.0);
    scale.setToY(1.0);
    scale.setInterpolator(Interpolator.EASE_OUT);
    
    // Fade out after delay
    PauseTransition pause = new PauseTransition(Duration.millis(2000));
    pause.setOnFinished(e -> {
      FadeTransition fadeOut = new FadeTransition(Duration.millis(500), notification);
      fadeOut.setToValue(0.0);
      fadeOut.setOnFinished(event -> contentPane.getChildren().remove(notification));
      fadeOut.play();
    });
    
    // Play animations
    ParallelTransition parallel = new ParallelTransition(slideDown, scale);
    SequentialTransition sequence = new SequentialTransition(parallel, pause);
    sequence.play();
  }
  
  private void showSaveErrorAnimation() {
    // Remove saving overlay
    contentPane.getChildren().removeIf(node -> node instanceof StackPane && ((StackPane) node).getChildren().get(0) instanceof Label);
    
    // Create error notification
    Label errorLabel = new Label("✗ Save Failed!");
    errorLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: white; -fx-background-color: #F44336; -fx-padding: 15px 25px; -fx-background-radius: 25px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);");
    
    StackPane notification = new StackPane(errorLabel);
    notification.setAlignment(Pos.TOP_CENTER);
    notification.setPadding(new Insets(50, 0, 0, 0));
    notification.setMouseTransparent(true);
    
    contentPane.getChildren().add(notification);
    AnchorPane.setTopAnchor(notification, 0.0);
    AnchorPane.setLeftAnchor(notification, 0.0);
    AnchorPane.setRightAnchor(notification, 0.0);
    
    // Shake animation
    TranslateTransition shake = new TranslateTransition(Duration.millis(100), notification);
    shake.setFromX(0);
    shake.setByX(10);
    shake.setCycleCount(6);
    shake.setAutoReverse(true);
    
    // Fade out after delay
    PauseTransition pause = new PauseTransition(Duration.millis(2500));
    pause.setOnFinished(e -> {
      FadeTransition fadeOut = new FadeTransition(Duration.millis(500), notification);
      fadeOut.setToValue(0.0);
      fadeOut.setOnFinished(event -> contentPane.getChildren().remove(notification));
      fadeOut.play();
    });
    
    SequentialTransition sequence = new SequentialTransition(shake, pause);
    sequence.play();
  }
  
  private void saveToFile(File file) {
    try {
      FileWriter fw = new FileWriter(file);
      BufferedWriter bw = new BufferedWriter(fw);
      bw.write(textArea.getText());
      bw.flush();
      bw.close();
      primaryStage.setTitle("Text Editor - " + file.getName());
      
      // Reset the changed flag since we just saved
      textChanged = false;
      
      // Save the path of the current file for recovery
      prefs.put(LAST_FILE_KEY, file.getAbsolutePath());
      
      // Log file access
      logFileAccess(file.getAbsolutePath());
    } catch (IOException e) {
      showError("Save Error", "Can't save your file", e.getMessage());
    }
  }
  
  private void openFile() {
    FileChooser fileChooser = new FileChooser();
    File file = fileChooser.showOpenDialog(primaryStage);
    if (file != null) {
      openFileFromPath(file);
    }
  }
  
  private void openFileFromPath(File file) {
    // Check for unsaved changes before opening a new file
    if (textChanged) {
      Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
      alert.setTitle("Unsaved Changes");
      alert.setHeaderText("You have unsaved changes");
      alert.setContentText("Would you like to save before opening a new file?");
      
      ButtonType saveButton = new ButtonType("Save");
      ButtonType dontSaveButton = new ButtonType("Don't Save");
      ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
      
      alert.getButtonTypes().setAll(saveButton, dontSaveButton, cancelButton);
      
      alert.showAndWait().ifPresent(buttonType -> {
        if (buttonType == saveButton) {
          // Save current file before opening new one
          saveFile();
          loadFile(file);
        } else if (buttonType == dontSaveButton) {
          // Open new file without saving current one
          loadFile(file);
        }
        // If cancel, do nothing
      });
    } else {
      // No unsaved changes, proceed with opening the file
      loadFile(file);
    }
  }
  
  private void loadFile(File file) {
    try {
      textArea.clear();
      FileReader fr = new FileReader(file);
      BufferedReader br = new BufferedReader(fr);
      String line;
      while ((line = br.readLine()) != null) {
        textArea.appendText(line + "\n");
      }
      br.close();
      
      // Update line numbers
      updateLineNumbers();
      
      // Set the current file for auto-save
      currentFile = file;
      primaryStage.setTitle("Text Editor - " + file.getName());
      
      // Reset the changed flag since we just loaded the file
      textChanged = false;
      
      // Save the path of the current file for recovery
      prefs.put(LAST_FILE_KEY, file.getAbsolutePath());
    } catch (IOException e) {
      showError("Open Error", "Can't open the file", e.getMessage());
    }
  }
  
  private void deleteFileWithAnimation() {
    FileChooser fileChooser = new FileChooser();
    File file = fileChooser.showOpenDialog(primaryStage);
    if (file != null) {
      showDeleteConfirmation(file);
    }
  }
  
  private void showDeleteConfirmation(File file) {
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
    alert.setTitle("Delete File");
    alert.setHeaderText("Are you sure you want to delete this file?");
    alert.setContentText("File: " + file.getName() + "\nThis action cannot be undone.");
    
    ButtonType deleteButton = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
    ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
    alert.getButtonTypes().setAll(deleteButton, cancelButton);
    
    alert.showAndWait().ifPresent(buttonType -> {
      if (buttonType == deleteButton) {
        performDeleteWithAnimation(file);
      }
    });
  }
  
  private void performDeleteWithAnimation(File file) {
    showDeletingAnimation();
    
    Task<Boolean> deleteTask = new Task<Boolean>() {
      @Override
      protected Boolean call() throws Exception {
        Thread.sleep(800);
        return file.delete();
      }
    };
    
    deleteTask.setOnSucceeded(event -> {
      boolean success = deleteTask.getValue();
      if (success) {
        showDeleteSuccessAnimation(file.getName());
      } else {
        showDeleteErrorAnimation();
      }
    });
    
    new Thread(deleteTask).start();
  }
  
  private void showDeletingAnimation() {
    Label deletingLabel = new Label("Deleting...");
    deletingLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: #FF5722; -fx-background-color: rgba(255,255,255,0.9); -fx-padding: 10px; -fx-background-radius: 5px;");
    
    StackPane overlay = new StackPane(deletingLabel);
    overlay.setStyle("-fx-background-color: rgba(0,0,0,0.3);");
    
    contentPane.getChildren().add(overlay);
    AnchorPane.setTopAnchor(overlay, 0.0);
    AnchorPane.setBottomAnchor(overlay, 0.0);
    AnchorPane.setLeftAnchor(overlay, 0.0);
    AnchorPane.setRightAnchor(overlay, 0.0);
    
    FadeTransition fadeIn = new FadeTransition(Duration.millis(200), overlay);
    fadeIn.setFromValue(0.0);
    fadeIn.setToValue(1.0);
    fadeIn.play();
    
    ScaleTransition pulse = new ScaleTransition(Duration.millis(600), deletingLabel);
    pulse.setFromX(1.0);
    pulse.setFromY(1.0);
    pulse.setToX(1.2);
    pulse.setToY(1.2);
    pulse.setCycleCount(Timeline.INDEFINITE);
    pulse.setAutoReverse(true);
    pulse.play();
  }
  
  private void showDeleteSuccessAnimation(String fileName) {
    contentPane.getChildren().removeIf(node -> node instanceof StackPane && ((StackPane) node).getChildren().get(0) instanceof Label);
    
    Label successLabel = new Label("✓ File Deleted Successfully!");
    successLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: white; -fx-background-color: #FF5722; -fx-padding: 15px 25px; -fx-background-radius: 25px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);");
    
    Label fileLabel = new Label(fileName + " has been deleted");
    fileLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666; -fx-padding: 5px 0 0 0;");
    
    VBox notificationContent = new VBox(5, successLabel, fileLabel);
    notificationContent.setAlignment(Pos.CENTER);
    
    StackPane notification = new StackPane(notificationContent);
    notification.setAlignment(Pos.TOP_CENTER);
    notification.setPadding(new Insets(50, 0, 0, 0));
    notification.setMouseTransparent(true);
    
    contentPane.getChildren().add(notification);
    AnchorPane.setTopAnchor(notification, 0.0);
    AnchorPane.setLeftAnchor(notification, 0.0);
    AnchorPane.setRightAnchor(notification, 0.0);
    
    TranslateTransition slideDown = new TranslateTransition(Duration.millis(400), notification);
    slideDown.setFromY(-150);
    slideDown.setToY(0);
    slideDown.setInterpolator(Interpolator.EASE_OUT);
    
    ScaleTransition scale = new ScaleTransition(Duration.millis(300), notificationContent);
    scale.setFromX(0.8);
    scale.setFromY(0.8);
    scale.setToX(1.0);
    scale.setToY(1.0);
    scale.setInterpolator(Interpolator.EASE_OUT);
    
    PauseTransition pause = new PauseTransition(Duration.millis(2500));
    pause.setOnFinished(e -> {
      FadeTransition fadeOut = new FadeTransition(Duration.millis(500), notification);
      fadeOut.setToValue(0.0);
      fadeOut.setOnFinished(event -> {
        contentPane.getChildren().remove(notification);
        textArea.clear();
        currentFile = null;
        primaryStage.setTitle("Text Editor");
      });
      fadeOut.play();
    });
    
    ParallelTransition parallel = new ParallelTransition(slideDown, scale);
    SequentialTransition sequence = new SequentialTransition(parallel, pause);
    sequence.play();
  }
  
  private void showDeleteErrorAnimation() {
    contentPane.getChildren().removeIf(node -> node instanceof StackPane && ((StackPane) node).getChildren().get(0) instanceof Label);
    
    Label errorLabel = new Label("✗ Delete Failed!");
    errorLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: white; -fx-background-color: #F44336; -fx-padding: 15px 25px; -fx-background-radius: 25px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);");
    
    Label messageLabel = new Label("Could not delete the file. Please try again.");
    messageLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666; -fx-padding: 5px 0 0 0;");
    
    VBox notificationContent = new VBox(5, errorLabel, messageLabel);
    notificationContent.setAlignment(Pos.CENTER);
    
    StackPane notification = new StackPane(notificationContent);
    notification.setAlignment(Pos.TOP_CENTER);
    notification.setPadding(new Insets(50, 0, 0, 0));
    notification.setMouseTransparent(true);
    
    contentPane.getChildren().add(notification);
    AnchorPane.setTopAnchor(notification, 0.0);
    AnchorPane.setLeftAnchor(notification, 0.0);
    AnchorPane.setRightAnchor(notification, 0.0);
    
    TranslateTransition shake = new TranslateTransition(Duration.millis(100), notification);
    shake.setFromX(0);
    shake.setByX(10);
    shake.setCycleCount(6);
    shake.setAutoReverse(true);
    
    PauseTransition pause = new PauseTransition(Duration.millis(3000));
    pause.setOnFinished(e -> {
      FadeTransition fadeOut = new FadeTransition(Duration.millis(500), notification);
      fadeOut.setToValue(0.0);
      fadeOut.setOnFinished(event -> contentPane.getChildren().remove(notification));
      fadeOut.play();
    });
    
    SequentialTransition sequence = new SequentialTransition(shake, pause);
    sequence.play();
  }
  
  private void showError(String title, String header, String content) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle(title);
    alert.setHeaderText(header);
    alert.setContentText(content);
    alert.showAndWait();
  }
  
  private void saveRecoveryFile() {
    try {
      // Save current text to recovery file
      FileWriter fw = new FileWriter(RECOVERY_FILE_PATH);
      BufferedWriter bw = new BufferedWriter(fw);
      bw.write(textArea.getText());
      bw.flush();
      bw.close();
      
      // Save additional recovery info
      if (currentFile != null) {
        FileWriter infoFw = new FileWriter(RECOVERY_INFO_PATH);
        BufferedWriter infoBw = new BufferedWriter(infoFw);
        infoBw.write(currentFile.getAbsolutePath());
        infoBw.flush();
        infoBw.close();
      }
      
      System.out.println("Recovery file saved");
    } catch (IOException e) {
      System.err.println("Failed to save recovery file: " + e.getMessage());
    }
  }
  
  /**
   * Attempts to find the background image from various possible locations
   * @return InputStream of the background image or null if not found
   */
  private InputStream findBackgroundImage() {
    // Try different possible locations for the background image
    InputStream imageStream = null;
    
    // Check custom path first if specified
    if (CUSTOM_ASSETS_PATH != null) {
      try {
        File imageFile = new File(CUSTOM_ASSETS_PATH, BACKGROUND_IMAGE_NAME);
        if (imageFile.exists()) {
          System.out.println("Found background image in custom path: " + imageFile.getAbsolutePath());
          return new FileInputStream(imageFile);
        }
      } catch (FileNotFoundException e) {
        // Continue to next location
      }
    }
    
    // 1. Try in the specific assets folder in Linux user's home directory
    try {
      // Get Linux username
      String username = System.getProperty("user.name");
      // Construct Linux home path
      String linuxHomeDir = "/home/" + username;
      
      File assetsDir = new File(linuxHomeDir, BACKGROUND_FOLDER);
      File imageFile = new File(assetsDir, BACKGROUND_IMAGE_NAME);
      
      if (imageFile.exists()) {
        System.out.println("Found background image in Linux home directory: " + imageFile.getAbsolutePath());
        return new FileInputStream(imageFile);
      }
    } catch (FileNotFoundException e) {
      // Continue to next location
    }
    
    // 2. Try in the standard home directory as fallback
    try {
      String homeDir = System.getProperty("user.home");
      File assetsDir = new File(homeDir, BACKGROUND_FOLDER);
      File imageFile = new File(assetsDir, BACKGROUND_IMAGE_NAME);
      
      if (imageFile.exists()) {
        System.out.println("Found background image in home directory: " + imageFile.getAbsolutePath());
        return new FileInputStream(imageFile);
      }
    } catch (FileNotFoundException e) {
      // Continue to next location
    }
    
    // 2. Try in the application directory
    try {
      String appDir = System.getProperty("user.dir");
      File assetsDir = new File(appDir, BACKGROUND_FOLDER);
      File imageFile = new File(assetsDir, BACKGROUND_IMAGE_NAME);
      
      if (imageFile.exists()) {
        System.out.println("Found background image in app directory: " + imageFile.getAbsolutePath());
        return new FileInputStream(imageFile);
      }
    } catch (FileNotFoundException e) {
      // Continue to next location
    }
    
    // 3. Try the standard resource paths
    imageStream = getClass().getResourceAsStream("/com/javafx/fxintellij/" + BACKGROUND_IMAGE_NAME);
    if (imageStream != null) {
      System.out.println("Found background image in resources");
      return imageStream;
    }
    
    imageStream = getClass().getResourceAsStream("/" + BACKGROUND_IMAGE_NAME);
    if (imageStream != null) return imageStream;
    
    imageStream = getClass().getResourceAsStream(BACKGROUND_IMAGE_NAME);
    if (imageStream != null) return imageStream;
    
    // 4. Try the class loader directly
    imageStream = getClass().getClassLoader().getResourceAsStream(BACKGROUND_IMAGE_NAME);
    if (imageStream != null) return imageStream;
    
    // 5. Try in various file system locations as fallbacks
    try {
      // Current directory
      File file = new File(BACKGROUND_IMAGE_NAME);
      if (file.exists()) {
        return new FileInputStream(file);
      }
      
      // Application directory
      String currentDir = System.getProperty("user.dir");
      file = new File(currentDir, BACKGROUND_IMAGE_NAME);
      if (file.exists()) {
        return new FileInputStream(file);
      }
      
      // Resources directory
      file = new File(currentDir, "src/main/resources/" + BACKGROUND_IMAGE_NAME);
      if (file.exists()) {
        return new FileInputStream(file);
      }
      
      // Class directory
      file = new File(currentDir, "src/main/java/com/javafx/fxintellij/" + BACKGROUND_IMAGE_NAME);
      if (file.exists()) {
        return new FileInputStream(file);
      }
    } catch (FileNotFoundException e) {
      // Ignore and return null
    }
    
    return null;
  }
  
  /**
   * Attempts to load FXML from custom path
   * @param loader The FXMLLoader to use
   * @return Parent object if successful, null if not found
   */
  private Parent loadFXMLFromCustomPath(FXMLLoader loader) {
    // Check custom path first if specified
    if (CUSTOM_ASSETS_PATH != null) {
      try {
        File fxmlFile = new File(CUSTOM_ASSETS_PATH, FXML_FILE_NAME);
        if (fxmlFile.exists()) {
          System.out.println("Loading FXML from custom path: " + fxmlFile.getAbsolutePath());
          loader.setLocation(fxmlFile.toURI().toURL());
          return loader.load();
        }
      } catch (Exception e) {
        System.err.println("Failed to load FXML from custom path: " + e.getMessage());
        // Continue to next location
      }
    }
    
    // Try in the specific assets folder in Linux user's home directory
    try {
      // Get Linux username
      String username = System.getProperty("user.name");
      // Construct Linux home path
      String linuxHomeDir = "/home/" + username;
      
      File assetsDir = new File(linuxHomeDir, BACKGROUND_FOLDER);
      File fxmlFile = new File(assetsDir, FXML_FILE_NAME);
      
      if (fxmlFile.exists()) {
        System.out.println("Loading FXML from Linux home directory: " + fxmlFile.getAbsolutePath());
        loader.setLocation(fxmlFile.toURI().toURL());
        return loader.load();
      }
    } catch (Exception e) {
      // Continue to next location
    }
    
    // Try in the standard home directory as fallback
    try {
      String homeDir = System.getProperty("user.home");
      File assetsDir = new File(homeDir, BACKGROUND_FOLDER);
      File fxmlFile = new File(assetsDir, FXML_FILE_NAME);
      
      if (fxmlFile.exists()) {
        System.out.println("Loading FXML from home directory: " + fxmlFile.getAbsolutePath());
        loader.setLocation(fxmlFile.toURI().toURL());
        return loader.load();
      }
    } catch (Exception e) {
      // Continue to next location
    }
    
    // Try in the application directory
    try {
      String appDir = System.getProperty("user.dir");
      File assetsDir = new File(appDir, BACKGROUND_FOLDER);
      File fxmlFile = new File(assetsDir, FXML_FILE_NAME);
      
      if (fxmlFile.exists()) {
        System.out.println("Loading FXML from app directory: " + fxmlFile.getAbsolutePath());
        loader.setLocation(fxmlFile.toURI().toURL());
        return loader.load();
      }
    } catch (Exception e) {
      // Continue to next location
    }
    
    return null;
  }
  
  private void checkForRecovery() {
    File recoveryFile = new File(RECOVERY_FILE_PATH);
    File recoveryInfoFile = new File(RECOVERY_INFO_PATH);
    
    if (recoveryFile.exists() && recoveryFile.length() > 0) {
      // There's a recovery file, ask user if they want to recover
      Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
      alert.setTitle("Recover Previous Session");
      alert.setHeaderText("Unsaved work from previous session found");
      alert.setContentText("Would you like to recover your unsaved work?");
      
      ButtonType recoverButton = new ButtonType("Recover");
      ButtonType discardButton = new ButtonType("Discard");
      
      alert.getButtonTypes().setAll(recoverButton, discardButton);
      
      alert.showAndWait().ifPresent(buttonType -> {
        if (buttonType == recoverButton) {
          // Load recovery file content
          try {
            textArea.clear();
            FileReader fr = new FileReader(recoveryFile);
            BufferedReader br = new BufferedReader(fr);
            String line;
            while ((line = br.readLine()) != null) {
              textArea.appendText(line + "\n");
            }
            br.close();
            
            // Update line numbers
            updateLineNumbers();
            
            // Try to get the file path from recovery info
            if (recoveryInfoFile.exists()) {
              FileReader infoFr = new FileReader(recoveryInfoFile);
              BufferedReader infoBr = new BufferedReader(infoFr);
              String filePath = infoBr.readLine();
              infoBr.close();
              
              if (filePath != null && !filePath.isEmpty()) {
                File lastFile = new File(filePath);
                if (lastFile.exists()) {
                  currentFile = lastFile;
                  primaryStage.setTitle("Text Editor - " + lastFile.getName() + " (Recovered)");
                }
              }
            } else {
              // Fall back to preferences if info file doesn't exist
              String lastFilePath = prefs.get(LAST_FILE_KEY, null);
              if (lastFilePath != null) {
                File lastFile = new File(lastFilePath);
                if (lastFile.exists()) {
                  currentFile = lastFile;
                  primaryStage.setTitle("Text Editor - " + lastFile.getName() + " (Recovered)");
                }
              }
            }
            
          } catch (IOException e) {
            showError("Recovery Error", "Failed to recover file", e.getMessage());
          }
        }
        
        // Delete recovery files regardless of choice
        try {
          Files.deleteIfExists(Paths.get(RECOVERY_FILE_PATH));
          Files.deleteIfExists(Paths.get(RECOVERY_INFO_PATH));
        } catch (IOException e) {
          System.err.println("Failed to delete recovery files: " + e.getMessage());
        }
      });
    } else {
      // No recovery needed, check if we should open last file
      String lastFilePath = prefs.get(LAST_FILE_KEY, null);
      if (lastFilePath != null) {
        File lastFile = new File(lastFilePath);
        if (lastFile.exists()) {
          Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
          alert.setTitle("Open Last File");
          alert.setHeaderText("Open last edited file?");
          alert.setContentText("Would you like to open: " + lastFile.getName());
          
          ButtonType openButton = new ButtonType("Open");
          ButtonType cancelButton = new ButtonType("Cancel");
          
          alert.getButtonTypes().setAll(openButton, cancelButton);
          
          alert.showAndWait().ifPresent(buttonType -> {
            if (buttonType == openButton) {
              openFileFromPath(lastFile);
            }
          });
        }
      }
    }
  }
  

}
