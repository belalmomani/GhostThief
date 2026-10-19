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
// رقم الإصدار يزيد تلقائياً مع كل بناء على GitHub (GITHUB_RUN_NUMBER) فيُثبَّت كتحديث فوق القديم.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val hasKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.ghostthief.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostthief.game"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber * 10
        versionName = "0.3.$runNumber"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebase.toString())
    }

    buildFeatures { buildConfig = true }

    flavorDimensions += "ads"
    productFlavors {
        // النسخة الافتراضية: بلا إعلانات ولا أي SDK إعلاني (الأنسب لألعاب الأطفال)
        create("noads") {
            dimension = "ads"
            versionCode = runNumber * 10
            buildConfigField("boolean", "ADS_ENABLED", "false")
            buildConfigField("String", "STARTIO_APP_ID", "\"\"")
        }
        // نسخة تجريبية مع Start.io (وضع child-directed مفعّل في الـ manifest الخاص بها)
        create("ads") {
            dimension = "ads"
            versionCode = runNumber * 10 + 1
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
            // مفتاحك (إن وُجد في الأسرار) وإلا المفتاح التجريبي الثابت
            signingConfig = signingConfigs.getByName(if (hasKeystore) "release" else "stable")
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

    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Start.io — فقط في نكهة ads. راجع وثائق Start.io: com.startapp:inapp-sdk:5.+
    "adsImplementation"("com.startapp:inapp-sdk:5.+")

    testImplementation("junit:junit:4.13.2")
}
