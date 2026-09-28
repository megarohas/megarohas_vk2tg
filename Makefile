PKG       := com.megarohas.vk2tg
SDK       ?= $(HOME)/Library/Android/sdk
BT        ?= $(SDK)/build-tools/36.0.0
PLATFORM  ?= $(SDK)/platforms/android-36/android.jar
KEYSTORE  ?= $(HOME)/.android/debug.keystore
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17)
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

# Several transports to one device (ip:port + mdns name) → set ADB_SERIAL=ip:port
ADB := adb $(if $(ADB_SERIAL),-s $(ADB_SERIAL))

B   := build
APK := $(B)/vk2tg.apk
SRC := $(shell find src -name '*.java')
RES := $(shell find res -type f)

.PHONY: all install grant status log clean

all: $(APK)

# No Gradle: aapt2 → javac → d8 → zipalign → apksigner, a few seconds end to end.
$(APK): AndroidManifest.xml $(SRC) $(RES)
	rm -rf $(B) && mkdir -p $(B)/classes
	$(BT)/aapt2 compile --dir res -o $(B)/res.zip
	$(BT)/aapt2 link -I $(PLATFORM) --manifest AndroidManifest.xml -o $(B)/app.unsigned.apk $(B)/res.zip
	javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath $(PLATFORM):$(BT)/core-lambda-stubs.jar -d $(B)/classes $(SRC)
	$(BT)/d8 --release --min-api 29 --lib $(PLATFORM) --output $(B) $$(find $(B)/classes -name '*.class')
	cd $(B) && zip -q app.unsigned.apk classes.dex
	$(BT)/zipalign -f -p 4 $(B)/app.unsigned.apk $(B)/app.aligned.apk
	$(BT)/apksigner sign --ks $(KEYSTORE) --ks-pass pass:android --ks-key-alias androiddebugkey \
		--key-pass pass:android --out $@ $(B)/app.aligned.apk
	@ls -lh $@

install: $(APK)
	$(ADB) install -r $(APK)

# Notification access + no battery restrictions (network stays up in Doze)
grant:
	$(ADB) shell cmd notification allow_listener $(PKG)/.VkListener
	$(ADB) shell dumpsys deviceidle whitelist +$(PKG)

status:
	$(ADB) shell am broadcast -n $(PKG)/.AdbConfig

log:
	$(ADB) logcat -s vk2tg

clean:
	rm -rf $(B)
