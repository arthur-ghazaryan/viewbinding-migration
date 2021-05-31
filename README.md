# viewbinding-migration

Android Studio plugin to help migrate from synthetics to view binding


Plugin works with android studio 4.2 build number **202.7660.26**

plugin located in [artifacts](https://github.com/arthur-ghazaryan/viewbinding-migration/tree/master/artifacts) folder

# How it works

Currently plugin works only with fragments. Action is located under generate menu group (Cmd + N). Running this action on fragment file will generate neccesary binding property with non null getter like this

```kotlin
private var _binding: ResultProfileBinding? = null
// This property is only valid between onCreateView and
// onDestroyView.
private val binding get() = _binding!! 
```
It also will try to override onDestroyView method if it does not exist and will add necessary null assignment

```kotlin
override fun onDestroyView() {
    super.onDestroyView()
    _binding = null //when onDestroyView already overrided plugin will add only this line
}
```

All synthetics then will be replaced with correct binding properties using camelcase syntax

```kotlin
my_view_id -> myViewId
``` 

# ⚠️ WIP warning

The plugin is under heavy development, code is messy and should be refactored in the future, some features like nullable binding properties and activity migrations, nested layouts also in wip but you free to try and share some feedback 🙂
