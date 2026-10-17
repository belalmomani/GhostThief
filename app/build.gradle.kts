plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// يُفعَّل Firebase فقط إذا وضعتَ ملف google-services.json الخاص بك داخل مجلد app/
val hasFirebase = file("google-services.json").exists()
if (hasFirebase) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.ghostthief.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostthief.game"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebase.toString())
    }

    buildFeatures { buildConfig = true }

    flavorDimensions += "ads"
    productFlavors {
        // النسخة الافتراضية: بلا إعلانات ولا أي SDK إعلاني (الأنسب لألعاب الأطفال)
        create("noads") {
            dimension = "ads"
            buildConfigField("boolean", "ADS_ENABLED", "false")
            buildConfigField("String", "STARTIO_APP_ID", "\"\"")
        }
        // نسخة تجريبية مع Start.io (وضع child-directed مفعّل في الـ manifest الخاص بها)
        create("ads") {
            dimension = "ads"
            buildConfigField("boolean", "ADS_ENABLED", "true")
            val appId = (project.findProperty("STARTIO_APP_ID") as String?) ?: ""
            buildConfigField("String", "STARTIO_APP_ID", "\"$appId\"")
        }
    }

    buildTypes {
        release {
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
