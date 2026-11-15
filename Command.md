此文件是用户自己创建的备忘

并行编译并复制到下载的命令：

```sh
cd ~/Projects/ExpenseTracker/android && \
./gradlew :app:assembleDebug :app:assembleRelease && \
./gradlew --stop && \
cp app/build/outputs/apk/debug/app-debug.apk "$(xdg-user-dir DOWNLOAD)/yohaku-dev.apk" && \
cp app/build/outputs/apk/release/app-release.apk "$(xdg-user-dir DOWNLOAD)/yohaku-release.apk"
```