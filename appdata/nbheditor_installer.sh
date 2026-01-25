#!/bin/bash
echo Initialising The Noywrit editor Installer. Please Be petient and connect your pc to internet
echo Installing the text editor
cd $HOME/$find -iname nbheditor
tar -xvf nbheditor.tar.xz -C $HOME/Downloads/
echo Installing java
cd $HOME/Downloads/nbheditor && sudo apt install ./jdk-21_linux-x64_bin.deb -y
cd $HOME/Downloads/nbheditor && sudo zypper install ./jdk-21_linux-x64_bin.rpm -y
cd $HOME/Downloads/nbheditor && sudo yum localinstall ./jdk-21_linux-x64_bin.rpm -y
$(command sudo cp $HOME/Downloads/nbheditor/nbheditor.desktop /usr/share/applications/);
$(command sudo cp $HOME/Downloads/nbheditor/Icon.png /usr/share/applications/);
echo Installation Done!

