import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.devagent.rkqxpl"
    minSdk = 24
    targetSdk = 36
    // CI sobrescreve via -PversionCode / -PversionName quando disponível
    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 2
    versionName = (project.findProperty("versionName") as String?) ?: "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Baixa e empacota automaticamente o motor nativo Android arm64 do llama.cpp (libllama_server.so + libs)
// em src/main/jniLibs/arm64-v8a durante o assembleDebug/assembleRelease sem precisar alterar .github/workflows.
val prepareLlamaNativeEngine by tasks.registering {
  val jniArm64Dir = file("src/main/jniLibs/arm64-v8a")
  onlyIf { !file("src/main/jniLibs/arm64-v8a/libllama_server.so").exists() }
  doLast {
    jniArm64Dir.mkdirs()
    try {
      val pyScript = listOf(
        "import json, os, shutil, sys, tarfile, urllib.request",
        "jni_dir = sys.argv[1]",
        "os.makedirs(jni_dir, exist_ok=True)",
        "req = urllib.request.Request('https://api.github.com/repos/ggml-org/llama.cpp/releases/latest', headers={'User-Agent': 'DevAgent-Gradle'})",
        "data = json.loads(urllib.request.urlopen(req, timeout=30).read().decode('utf-8'))",
        "urls = [a.get('browser_download_url') for a in data.get('assets', []) if a.get('name', '').endswith('-bin-android-arm64.tar.gz')]",
        "if urls:",
        "    archive = '/tmp/llama-android-arm64.tar.gz'",
        "    urllib.request.urlretrieve(urls[0], archive)",
        "    extract_dir = '/tmp/llama_android_extract'",
        "    shutil.rmtree(extract_dir, ignore_errors=True)",
        "    os.makedirs(extract_dir, exist_ok=True)",
        "    tarfile.open(archive, 'r:gz').extractall(extract_dir)",
        "    for root, _, files in os.walk(extract_dir):",
        "        for f in files:",
        "            full = os.path.realpath(os.path.join(root, f))",
        "            if not os.path.isfile(full): continue",
        "            if f == 'llama-server':",
        "                dst = os.path.join(jni_dir, 'libllama_server.so')",
        "                shutil.copy2(full, dst); os.chmod(dst, 0o755)",
        "            elif f.startswith('lib') and '.so' in f:",
        "                dst = os.path.join(jni_dir, f.split('.so')[0] + '.so')",
        "                shutil.copy2(full, dst); os.chmod(dst, 0o755)",
        "for nr in ['/usr/local/lib/android/sdk/ndk', os.environ.get('ANDROID_HOME', '') + '/ndk', os.environ.get('ANDROID_SDK_ROOT', '') + '/ndk']:",
        "    if os.path.isdir(nr):",
        "        for r, _, fs in os.walk(nr):",
        "            if 'libc++_shared.so' in fs and 'aarch64-linux-android' in r:",
        "                dst = os.path.join(jni_dir, 'libc++_shared.so')",
        "                if not os.path.exists(dst): shutil.copy2(os.path.join(r, 'libc++_shared.so'), dst); os.chmod(dst, 0o755)",
        "                break"
      ).joinToString("\n")
      val proc = ProcessBuilder("python3", "-c", pyScript, jniArm64Dir.absolutePath).inheritIO().start()
      proc.waitFor()
    } catch (e: Exception) {
      println("Aviso: falha opcional ao empacotar llama-server: " + e.message)
    }
  }
}

tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }.configureEach {
  dependsOn(prepareLlamaNativeEngine)
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
