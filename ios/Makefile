# StreamBox iOS build entry points. Requires macOS + Xcode 16 + xcodegen.
.PHONY: project build test sim ipa clean

project:            ## generate StreamBox.xcodeproj from project.yml
	xcodegen generate

build: project      ## build the app for the iOS Simulator (unsigned)
	xcodebuild -project StreamBox.xcodeproj -scheme StreamBox \
	  -configuration Debug -destination "generic/platform=iOS Simulator" \
	  -derivedDataPath build/sim build CODE_SIGNING_ALLOWED=NO

test: project       ## run unit + UI tests on an iPhone simulator
	xcodebuild -project StreamBox.xcodeproj -scheme StreamBox \
	  -destination "platform=iOS Simulator,name=iPhone 16" test

ipa: project        ## unsigned device .app packaged as an .ipa payload
	xcodebuild -project StreamBox.xcodeproj -scheme StreamBox \
	  -configuration Release -destination "generic/platform=iOS" \
	  -derivedDataPath build/dev build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
	mkdir -p build/payload/Payload
	cp -R build/dev/Build/Products/Release-iphoneos/StreamBox.app build/payload/Payload/
	cd build/payload && zip -ry ../StreamBox-unsigned-device.ipa Payload
	@echo "Wrote build/StreamBox-unsigned-device.ipa"
	@echo "NOT directly installable: re-sign with your Apple ID via AltStore/SideStore."

clean:
	rm -rf build StreamBox.xcodeproj
