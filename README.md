# Auto Enhance (MVP)

Video select -> auto analyze -> brightness/contrast/saturation apply -> export -> Before/After preview.

## GitHub diye APK build (kono PC software lagbe na)

1. github.com e login kore **New repository** banan (name: `AutoEnhance`, Public/Private jekono).
2. **Add file > Upload files** e giye ei project er shob file/folder drag & drop korun
   (`.github` folder ta o dite hobe, ta na hole build shuru hobe na).
3. **Commit changes** chapun.
4. Repo er **Actions** tab e jan. "Build APK" workflow auto cholbe (3-6 minute).
5. Green tick hole oi run e click kore niche **Artifacts** theke `AutoEnhance-debug-apk` download korun.
6. Zip unzip kore `app-debug.apk` phone e pathan, install korun
   (Settings e "Install unknown apps" allow korte hobe).

Build fail korle: Actions > failed run > fail howa step e click kore error text copy kore Claude ke din.

## Code ta kothay ki kore

- `MainActivity.kt` : puro app logic (analyze, effect apply, export, preview)
- `app/build.gradle.kts` : dependencies (Media3 Transformer)
- `.github/workflows/build.yml` : online APK build

## Pore jora jabe

- Aro bhalo auto-analysis, LUT based color grading
- AI low-light / denoise model (TFLite)
