Here is the complete guide combined into a single, structured Markdown document. You can copy the code block below and save it as **`README.md`** or **`launcher-guide.md`**.

```markdown
# Noctalia-Style Android Launcher — Developer Guide & Blueprint

A technical guide and reference implementation for creating a minimalist, keyboard-driven Android home screen launcher inspired by Linux shell launchers like **Noctalia**, **Rofi**, and **Wofi**.

---

## 1. Development Environment & Minimum Requirements

You can build this project using either the standard full-featured IDE or a lightweight command-line setup.

### Software Requirements
- **JDK:** OpenJDK 17 or JDK 21
- **Minimum SDK:** API 26 (Android 8.0) — *Required for category detection (`ApplicationInfo.category`) and modern Jetpack Compose APIs.*
- **Compile SDK:** API 34 or API 35 (Android 14 / 15)

### Option A: Standard Setup (Recommended)
- **IDE:** Android Studio (Ladybug or newer)
- **Included Tools:** OpenJDK, Android SDK, Gradle, Layout Inspector, ADB
- **Hardware:** PC/Mac/Linux with 8 GB+ RAM (16 GB recommended)

### Option B: Bare-Bones Lightweight Setup (VS Code / CLI)
- **Editor:** VS Code (with *Kotlin Language* extension) or Neovim
- **Build Tools:** Android SDK Command-Line Tools (`sdkmanager`, `platform-tools`, `build-tools`) + Gradle Wrapper

> **Testing Tip:** Testing on a **physical Android device** via USB Debugging (`adb`) uses significantly less CPU and RAM than running an Android Emulator.

---

## 2. Android Manifest Setup

To allow the app to act as a default launcher and detect all installed applications on Android 11+ (API 30+), declare the following permissions and intent filters in `AndroidManifest.xml`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Permission to query all installed packages (Required for Android 11+) -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="Noctalia Launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.NoctaliaLauncher">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:clearTaskOnLaunch="true"
            android:stateNotNeeded="true">
            
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <!-- Registers app as Home Screen Launcher -->
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

    </application>
</manifest>
```

---

## 3. Data Models & Package Manager Retrieval

### `AppItem.kt`
Defines the structure for installed applications, categories, and pinning/usage statistics.

```kotlin
package com.example.noctalialauncher

import android.graphics.drawable.Drawable

enum class AppCategory {
    ALL, FAVORITES, AUDIO, CHAT, CODE, EDUCATION, GAMES, GRAPHICS, SYSTEM, WEB, OTHER
}

data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val category: AppCategory,
    val isPinned: Boolean = false,
    val launchCount: Int = 0
)
```

### `AppRepository.kt`
Retrieves installed apps via Android's `PackageManager` and maps system app categories into launcher categories.

```kotlin
package com.example.noctalialauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build

class AppRepository(private val context: Context) {

    fun getInstalledApps(): List<AppItem> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        return resolveInfos.mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            
            // Exclude self from launcher list
            if (packageName == context.packageName) return@mapNotNull null

            val label = resolveInfo.loadLabel(pm).toString()
            val icon = resolveInfo.loadIcon(pm)
            val appInfo = resolveInfo.activityInfo.applicationInfo
            val category = mapToAppCategory(appInfo)

            AppItem(
                label = label,
                packageName = packageName,
                icon = icon,
                category = category
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun mapToAppCategory(appInfo: ApplicationInfo): AppCategory {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return when (appInfo.category) {
                ApplicationInfo.CATEGORY_AUDIO -> AppCategory.AUDIO
                ApplicationInfo.CATEGORY_GAME -> AppCategory.GAMES
                ApplicationInfo.CATEGORY_IMAGE -> AppCategory.GRAPHICS
                ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.CHAT
                ApplicationInfo.CATEGORY_MAPS, ApplicationInfo.CATEGORY_NEWS -> AppCategory.WEB
                else -> AppCategory.OTHER
            }
        }
        return AppCategory.OTHER
    }

    fun launchApp(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            context.startActivity(launchIntent)
        }
    }
}
```

---

## 4. UI Implementation with Jetpack Compose

### `LauncherScreen.kt`
The main view containing the search bar, category navigation bar, application list, and result counter.

```kotlin
package com.example.noctalialauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

@Composable
fun LauncherScreen(
    apps: List<AppItem>,
    onAppClick: (AppItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(AppCategory.ALL) }

    // Filtering logic for Search & Categories
    val filteredApps = remember(searchQuery, selectedCategory, apps) {
        apps.filter { app ->
            val matchesCategory = (selectedCategory == AppCategory.ALL) || (app.category == selectedCategory)
            val matchesSearch = app.label.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF1E1E2E) // Dark Noctalia aesthetic background
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
        ) {
            // 1. Search Bar
            SearchBarComponent(
                query = searchQuery,
                onQueryChange = { searchQuery = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Horizontal Category Filter Bar
            CategoryBarComponent(
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. App List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppListItem(app = app, onClick = { onAppClick(app) })
                }
            }

            // 4. Footer Result Count
            Text(
                text = "${filteredApps.size} results",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun SearchBarComponent(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search entries... or use > for commands", color = Color.Gray) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF89B4FA),
            unfocusedBorderColor = Color(0xFF313244),
            focusedContainerColor = Color(0xFF181825),
            unfocusedContainerColor = Color(0xFF181825),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun CategoryBarComponent(
    selectedCategory: AppCategory,
    onCategorySelected: (AppCategory) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(AppCategory.values()) { category ->
            val isSelected = category == selectedCategory
            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                label = { Text(category.name.lowercase().capitalize(), fontSize = 12.sp) },
                colors = FilterChipDefaults.filterchipColors(
                    selectedContainerColor = Color(0xFFA6E3A1),
                    selectedLabelColor = Color(0xFF11111B),
                    containerColor = Color(0xFF313244),
                    labelColor = Color.White
                )
            )
        }
    }
}

@Composable
fun AppListItem(
    app: AppItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = Color(0xFF2B2B3D)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = app.icon.toBitmap().asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = app.category.name.lowercase().capitalize(),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            IconButton(onClick = { /* Handle Pin / Menu Options */ }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = Color.Gray
                )
            }
        }
    }
}
```

---

## 5. Main Activity Entry Point

### `MainActivity.kt`

```kotlin
package com.example.noctalialauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = AppRepository(this)

        setContent {
            val apps = remember { repository.getInstalledApps() }

            LauncherScreen(
                apps = apps,
                onAppClick = { app ->
                    repository.launchApp(app.packageName)
                }
            )
        }
    }
}
```

---

## 6. Key Features & Extensions

### Command Prefix System (`>`)
Implement keyboard-driven shell triggers inside your search bar state:
```kotlin
if (searchQuery.startsWith(">")) {
    val command = searchQuery.removePrefix(">").trim()
    when (command) {
        "wifi" -> openWifiSettings()
        "bluetooth" -> openBluetoothSettings()
        "lock" -> lockScreen()
    }
}
```

### Frequent App Usage Persistence
Store launch events inside a local **Room Database** or **DataStore** each time `repository.launchApp()` is invoked. You can then sort default search results by `launchCount DESC`.
```
