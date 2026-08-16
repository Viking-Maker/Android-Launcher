Based on the Noctalia / Linux shell design in your screenshot, here is a detailed, component-by-component UI/UX specification of the Android interface:

---

### 1. Window Container & Layout Architecture
* **Form Factor:** A floating, centralized modal card (resembling a HUD overlay or floating panel over the wallpaper) with generous outer margins.
* **Corner Radius:** **24dp – 28dp** (heavily rounded, modern squircle design).
* **Container Background:** Deep dark navy/charcoal with a soft translucent acrylic/glassmorphic effect (`#181825` with 90–95% opacity).
* **Border:** A hairline border (`1dp` stroke) in a muted dark-slate hue (`#313244` at 40% opacity) for subtle edge definition.
* **Internal Padding:** Consistent **16dp – 20dp** padding on all four sides.

---

### 2. The Color Palette

| Role | Color Name / Description | Approximate Hex |
| :--- | :--- | :--- |
| **Window Background** | Deep Midnight Charcoal | `#161623` |
| **Card / Chip Background** | Dark Slate / Elevated Surface | `#252538` |
| **Focused / Active App Card** | Pastel Mint / Seafoam Green | `#72E5BE` |
| **Active Category Pill** | Pastel Lilac / Soft Lavender | `#D6A8FF` |
| **Search Bar Border** | Muted Copper / Rose Gold Stroke | `#B47970` |
| **Primary Text (Inactive)** | Clean White / Light Gray | `#FFFFFF` / `#CDD6F4` |
| **Primary Text (Active)** | Deep Obsidian / Black | `#11111B` |
| **Secondary / Subtitle Text**| Muted Lavender Gray | `#8A8AAB` |
| **Footer Text** | Dim Slate Gray | `#6C7086` |

---

### 3. Top Header: Search Bar & Grid Action

#### A. The Search Input Field
* **Shape:** Pill-shaped rounded rectangle (`18dp – 20dp` corner radius).
* **Background:** Deep recessed black/navy (`#12121C`).
* **Outline:** Thin `1.5dp` border in a warm, muted copper/dusty rose accent (`#B47970`).
* **Placeholder Text:** *"Search entries... or use > for commands"* in muted lavender-gray (`#8A8AAB`), font size `14sp` regular.
* **Single-line:** Text input is strictly single-line, auto-focused on launch.

#### B. Right Utility Button
* **Placement:** Adjacent to the search bar on the far right.
* **Shape:** Squircle button (`14dp` radius) matching search bar height.
* **Icon:** $2 \times 2$ Grid / App Drawer icon (`GridView`) in muted slate tint.

---

### 4. Category Filter Bar (Horizontal Toolbar)

A horizontally scrollable row of compact squircle/pill action chips situated directly below the search bar.

* **Chip Dimensions:** Approximately `36dp` height $\times$ `40dp` width.
* **Corner Radius:** `10dp – 12dp`.
* **Gap Spacing:** `6dp – 8dp` between items.
* **States:**
  * **Selected Chip (e.g., Favorites/Pin):** Solid vibrant **Pastel Lilac** (`#D6A8FF`) background with a dark/black icon (`#11111B`).
  * **Unselected Chips:** Dark elevated surface (`#252538`) with light muted gray outline icons (`#A6ADC8`).
* **Icon Set (Left-to-Right):**
  1. **Pin** (*Favorites / Frequently Used*)
  2. **4-Square Grid** (*All Applications*)
  3. **Musical Note** (*Audio / Media Players*)
  4. **Chat Bubble** (*Social / Messaging*)
  5. **Code Brackets `< / >`** (*Development / Code Editors*)
  6. **Graduation Cap** (*Education / Reference*)
  7. **Game Controller** (*Games*)
  8. **Paintbrush** (*Graphics / Design*)
  9. **Wi-Fi / Radar** (*Network & Connectivity*)
  10. **Document / Page** (*Office / Text Files*)
  11. **Desktop Monitor** (*System Tools & Settings*)
  12. **Globe** (*Web Browsers*)
  13. **Three-Dots `...`** (*More / Overflow Categories*)

---

### 5. Application List View (Vertical Card Stack)

A vertical list (`LazyColumn`) with an **8dp** gap between app items.

```
┌───────────────────────────────────────────────────────────────┐
│ [Icon]  App Name (Bold)                             [Action]  │
│         Category / Subtitle (Muted)                           │
└───────────────────────────────────────────────────────────────┘
```

#### A. Standard / Unfocused App Card (e.g., VS Code, Spotify)
* **Shape:** Rounded rectangle card (`14dp – 16dp` radius).
* **Background:** Dark slate surface (`#252538`).
* **Left Icon:** High-resolution app icon (size `38dp \times 38dp`) with rounded corners.
* **Title:** Bold white sans-serif text (`15sp`, weight: SemiBold).
* **Subtitle / Description:** Directly beneath title in muted gray (`12sp`, weight: Regular), describing what the app does (e.g., *"Web Browser"*, *"Text Editor"*, *"File Manager"*).

#### B. Active / Focused App Card (e.g., Brave)
* **Background:** Full solid fill in **Pastel Seafoam / Mint Green** (`#72E5BE`).
* **Text Colors:** Inverted to **Deep Charcoal/Black** (`#11111B`) for maximum contrast and readability.
* **Right Action Badge:** A dark translucent pill/circular button (`#1B332B`) containing an auxiliary action icon (such as a pin toggle, disable/kill task icon, or context menu).

---

### 6. Footer Status Bar
* **Position:** Fixed at the bottom-left corner below the app list.
* **Typography:** `12sp` font, regular weight, in dim slate-gray (`#6C7086`).
* **Content:** Live count of matching items (e.g., *"19 results"*).

---

### 7. Typography & Interaction Rules
* **Font Family:** Clean geometric sans-serif (such as **Inter**, **Rubik**, or **Google Sans**).
* **Keyboard-First Navigation:** Pressing `Down` or `Up` on a physical/virtual keyboard moves the active mint-green highlight card up and down the list.
* **Immediate Search Filtering:** Typing in the search bar instantly filters both by app title and category tags without requiring a submit button.
* **Command Mode:** Typing `>` switches the list from apps to internal system commands (e.g., `> wifi`, `> bluetooth`, `> settings`, `> lock`).
