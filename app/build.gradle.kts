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
    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 2
    versionName = (project.findProperty("versionName") as String?) ?: "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
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

/**
 * Baixa o binário oficial Android arm64 do ggml-org/llama.cpp e empacota em
 * src/main/jniLibs/arm64-v8a/ como libllama_server.so (+ libs .so).
 * Android extrai jniLibs e permite executar lib*.so via ProcessBuilder.
 */
val prepareLlamaNativeEngine by tasks.registering {
  val jniArm64Dir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
  outputs.dir(jniArm64Dir)
  // Reexecuta se o server ainda não existir (CI limpo)
  outputs.upToDateWhen {
    jniArm64Dir.file("libllama_server.so").asFile.isFile &&
      jniArm64Dir.file("libllama_server.so").asFile.length() > 50_000L
  }
  doLast {
    val outDir = jniArm64Dir.asFile
    outDir.mkdirs()
    val serverSo = File(outDir, "libllama_server.so")
    if (serverSo.isFile && serverSo.length() > 50_000L) {
      logger.lifecycle("llama.cpp já empacotado: ${serverSo.absolutePath} (${serverSo.length()} bytes)")
      return@doLast
    }

    val pyScript = """
import json, os, shutil, sys, tarfile, urllib.request

jni_dir = sys.argv[1]
os.makedirs(jni_dir, exist_ok=True)

def fetch(url):
    req = urllib.request.Request(url, headers={
        'User-Agent': 'DevAgent-Gradle',
        'Accept': 'application/vnd.github+json',
    })
    return urllib.request.urlopen(req, timeout=60).read()

url = None
# Procura nas releases recentes o asset android-arm64 oficial
for page in range(1, 4):
    raw = fetch(f'https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=30&page={page}')
    releases = json.loads(raw.decode('utf-8'))
    for rel in releases:
        for a in rel.get('assets', []):
            name = a.get('name', '')
            if name.endswith('-bin-android-arm64.tar.gz') or name.endswith('bin-android-arm64.tar.gz'):
                url = a.get('browser_download_url')
                print('Encontrado:', name, 'tag=', rel.get('tag_name'))
                break
        if url:
            break
    if url:
        break

if not url:
    # Fallback pin conhecido (quando rate-limit ou asset sumiu do latest)
    url = 'https://github.com/ggml-org/llama.cpp/releases/download/b9782/llama-b9782-bin-android-arm64.tar.gz'
    print('Usando fallback pin b9782:', url)

archive = '/tmp/llama-android-arm64.tar.gz'
print('Baixando', url)
urllib.request.urlretrieve(url, archive)
extract_dir = '/tmp/llama_android_extract'
shutil.rmtree(extract_dir, ignore_errors=True)
os.makedirs(extract_dir, exist_ok=True)
tarfile.open(archive, 'r:gz').extractall(extract_dir)

copied = []
for root, _, files in os.walk(extract_dir):
    for f in files:
        full = os.path.realpath(os.path.join(root, f))
        if not os.path.isfile(full):
            continue
        # llama-server vira libllama_server.so (Android empacota/executa libs nativas)
        if f == 'llama-server' or f == 'llama-server.exe':
            dst = os.path.join(jni_dir, 'libllama_server.so')
            shutil.copy2(full, dst)
            os.chmod(dst, 0o755)
            copied.append(dst)
            print('server ->', dst, os.path.getsize(dst))
        elif f.endswith('.so') or (f.startswith('lib') and '.so' in f):
            # normaliza nome (libfoo.so.1 -> libfoo.so)
            base = f
            if '.so.' in f:
                base = f.split('.so')[0] + '.so'
            dst = os.path.join(jni_dir, base)
            if not os.path.exists(dst):
                shutil.copy2(full, dst)
                os.chmod(dst, 0o755)
                copied.append(dst)
                print('lib ->', dst)

# libc++_shared do NDK se disponível no runner
for nr in [
    os.environ.get('ANDROID_NDK_HOME', ''),
    os.environ.get('ANDROID_NDK_ROOT', ''),
    os.environ.get('ANDROID_HOME', '') + '/ndk',
    os.environ.get('ANDROID_SDK_ROOT', '') + '/ndk',
    '/usr/local/lib/android/sdk/ndk',
]:
    if not nr or not os.path.isdir(nr):
        continue
    for r, _, fs in os.walk(nr):
        if 'libc++_shared.so' in fs and 'aarch64-linux-android' in r:
            dst = os.path.join(jni_dir, 'libc++_shared.so')
            if not os.path.exists(dst):
                shutil.copy2(os.path.join(r, 'libc++_shared.so'), dst)
                os.chmod(dst, 0o755)
                print('libc++_shared ->', dst)
            break

server = os.path.join(jni_dir, 'libllama_server.so')
if not os.path.isfile(server) or os.path.getsize(server) < 50000:
    raise SystemExit('Falha: libllama_server.so não foi gerado após o download do llama.cpp android-arm64')
print('OK llama.cpp empacotado:', len(copied), 'arquivos')
""".trimIndent()

    val scriptFile = File(temporaryDir, "prepare_llama.py")
    scriptFile.writeText(pyScript)
    val proc = ProcessBuilder("python3", scriptFile.absolutePath, outDir.absolutePath)
      .inheritIO()
      .start()
    val code = proc.waitFor()
    if (code != 0 || !serverSo.isFile || serverSo.length() < 50_000L) {
      throw GradleException(
        "Não foi possível empacotar libllama_server.so (exit=$code). " +
          "Verifique rede/GitHub API e o asset *-bin-android-arm64.tar.gz."
      )
    }
    logger.lifecycle("llama.cpp OK: ${serverSo.length()} bytes")
  }
}

tasks.matching {
  it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders"
}.configureEach {
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
