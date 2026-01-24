#!/bin/bash
cd $HOME/Downloads/nbheditor

javac --module-path $HOME/Downloads/nbheditor/javafx-sdk-21/lib --add-modules javafx.controls,javafx.fxml texteditor.java
cd $HOME/Downloads/nbheditor/
 java --module-path $HOME/Downloads/nbheditor/javafx-sdk-21/lib --add-modules javafx.controls,javafx.fxml  texteditor


