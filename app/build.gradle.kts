plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// يُفعَّل Firebase فقط إذا وضعتَ ملف google-services.json الخاص بك داخل مجلد app/
val hasFirebase = file("google-services.json").exists()
if (hasFirebase) {
    apply(plugin = "com.google.gms.google-services")
}

// التوقيع بمفتاحك الخاص: يُفعَّل فقط إذا وُجدت متغيرات البيئة (يضبطها GitHub Actions من الأسرار).
// لا تضع ملف المفتاح ولا كلمات المرور في المستودع أبداً.
// رقم الاسم للعرض فقط (GITHUB_RUN_NUMBER). يعود إلى 1 في أي مستودع جديد، فلا نعتمد عليه في versionCode.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

// versionCode يعتمد على وقت آخر commit (دقائق منذ 2026-01-01، يضبطه workflow في GT_BUILD_MINUTES).
// يزيد دائماً مهما كان المستودع أو عدّاد GitHub، فيُقبل البناء الجديد كتحديث فوق القديم بلا حذف.
val buildMinutes: Int = System.getenv("GT_BUILD_MINUTES")?.toIntOrNull()
    ?: ((System.currentTimeMillis() / 60_000L) - 29_453_760L).toInt().coerceAtLeast(1)

val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val hasKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

// اسم ملف الـ APK يحمل رقم الإصدار: GhostThief-v0.3.<رقم البناء>-...
base { archivesName.set("GhostThief-v0.3.$runNumber") }

android {
    namespace = "com.ghostthief.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostthief.game"
        minSdk = 24
        targetSdk = 34
        versionCode = buildMinutes * 10
        versionName = "0.3.$runNumber"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebase.toString())
    }

    buildFeatures { buildConfig = true }

    flavorDimensions += "ads"
    productFlavors {
        // النسخة الافتراضية: بلا إعلانات ولا أي SDK إعلاني (الأنسب لألعاب الأطفال)
        create("noads") {
            dimension = "ads"
            versionCode = buildMinutes * 10
            buildConfigField("boolean", "ADS_ENABLED", "false")
            buildConfigField("String", "STARTIO_APP_ID", "\"\"")
        }
        // نسخة تجريبية مع Start.io (وضع child-directed مفعّل في الـ manifest الخاص بها)
        create("ads") {
            dimension = "ads"
            versionCode = buildMinutes * 10 + 1
            buildConfigField("boolean", "ADS_ENABLED", "true")
            val appId = (project.findProperty("STARTIO_APP_ID") as String?) ?: ""
            buildConfigField("String", "STARTIO_APP_ID", "\"$appId\"")
        }
    }

    signingConfigs {
        // مفتاح تجريبي ثابت داخل المشروع: يجعل كل بناء بنفس التوقيع فيعمل التحديث فوق القديم بلا حذف.
        // للتجربة فقط — لا تنشر به على Google Play (استخدم مفتاحك عبر الأسرار).
        create("stable") {
            storeFile = file("ghostthief-debug.jks")
            storePassword = "ghostthief-debug"
            keyAlias = "ghostthief"
            keyPassword = "ghostthief-debug"
        }
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // دائماً المفتاح الثابت المضمّن: لا حاجة لأي أسرار، والتوقيع نفسه في كل مستودع وكل بناء
            // (نسخة release الموقّعة بمفتاحك الخاص لـ Google Play تبقى بالأسرار كما هي).
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // QR: توليد الرمز (مكتبة جافا صغيرة) + ماسح Google (بلا إذن كاميرا، يعمل على الجهاز)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Start.io — فقط في نكهة ads. الإصدار مثبّت (وليس 5.+) حتى لا يتغير مع كل بناء.
    "adsImplementation"("com.startapp:inapp-sdk:5.3.1")

    testImplementation("junit:junit:4.13.2")
}

// مكتبة Start.io (أو غيرها) قد تجلب kotlin-stdlib أحدث (مثل 2.4.x) من مترجم Kotlin 1.9.24 فيفشل البناء
// بخطأ "compiled with an incompatible version of Kotlin". نثبّت stdlib على إصدار المترجم نفسه.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("1.9.24")
        }
    }
}
