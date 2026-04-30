#!/bin/bash

# Download popular Google Fonts (Regular weight only to save space)
# Each font is ~100-200KB

cd /home/noywrit/AndroidStudioProjects/nbheditor.apps.beeta.com/appdata/Android/nbheditor/app/src/main/assets/fonts

# Download fonts from Google Fonts
wget -O roboto.ttf "https://github.com/google/roboto/raw/main/src/hinted/Roboto-Regular.ttf"
wget -O roboto_mono.ttf "https://github.com/google/roboto/raw/main/src/hinted/RobotoMono-Regular.ttf"
wget -O open_sans.ttf "https://github.com/google/fonts/raw/main/apache/opensans/OpenSans%5Bwdth%2Cwght%5D.ttf"
wget -O lato.ttf "https://github.com/google/fonts/raw/main/ofl/lato/Lato-Regular.ttf"
wget -O montserrat.ttf "https://github.com/google/fonts/raw/main/ofl/montserrat/Montserrat%5Bwght%5D.ttf"
wget -O source_sans_pro.ttf "https://github.com/google/fonts/raw/main/ofl/sourcesanspro/SourceSansPro-Regular.ttf"
wget -O raleway.ttf "https://github.com/google/fonts/raw/main/ofl/raleway/Raleway%5Bwght%5D.ttf"
wget -O ubuntu.ttf "https://github.com/google/fonts/raw/main/ufl/ubuntu/Ubuntu-Regular.ttf"
wget -O poppins.ttf "https://github.com/google/fonts/raw/main/ofl/poppins/Poppins-Regular.ttf"
wget -O nunito.ttf "https://github.com/google/fonts/raw/main/ofl/nunito/Nunito%5Bwght%5D.ttf"
wget -O playfair_display.ttf "https://github.com/google/fonts/raw/main/ofl/playfairdisplay/PlayfairDisplay%5Bwght%5D.ttf"
wget -O merriweather.ttf "https://github.com/google/fonts/raw/main/ofl/merriweather/Merriweather-Regular.ttf"
wget -O pt_sans.ttf "https://github.com/google/fonts/raw/main/ofl/ptsans/PTSans-Regular.ttf"
wget -O oswald.ttf "https://github.com/google/fonts/raw/main/ofl/oswald/Oswald%5Bwght%5D.ttf"
wget -O noto_sans.ttf "https://github.com/google/fonts/raw/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf"
wget -O fira_sans.ttf "https://github.com/google/fonts/raw/main/ofl/firasans/FiraSans-Regular.ttf"
wget -O inter.ttf "https://github.com/google/fonts/raw/main/ofl/inter/Inter%5Bslnt%2Cwght%5D.ttf"
wget -O work_sans.ttf "https://github.com/google/fonts/raw/main/ofl/worksans/WorkSans%5Bwght%5D.ttf"
wget -O quicksand.ttf "https://github.com/google/fonts/raw/main/ofl/quicksand/Quicksand%5Bwght%5D.ttf"
wget -O dancing_script.ttf "https://github.com/google/fonts/raw/main/ofl/dancingscript/DancingScript%5Bwght%5D.ttf"
wget -O pacifico.ttf "https://github.com/google/fonts/raw/main/ofl/pacifico/Pacifico-Regular.ttf"
wget -O indie_flower.ttf "https://github.com/google/fonts/raw/main/ofl/indieflower/IndieFlower-Regular.ttf"
wget -O caveat.ttf "https://github.com/google/fonts/raw/main/ofl/caveat/Caveat%5Bwght%5D.ttf"
wget -O comfortaa.ttf "https://github.com/google/fonts/raw/main/ofl/comfortaa/Comfortaa%5Bwght%5D.ttf"

echo "Fonts downloaded successfully!"
ls -lh
