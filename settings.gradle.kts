rootProject.name = "onvif-driver"

// Enable type-safe project accessors
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":common",
    ":designer",
    ":gateway"
)
