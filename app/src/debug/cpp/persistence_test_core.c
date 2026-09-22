/* Original synthetic libretro core used only by debug instrumentation tests. */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>

typedef bool (*environment_fn)(unsigned, void*);
typedef void (*video_fn)(const void*, unsigned, unsigned, size_t);
typedef size_t (*audio_fn)(const int16_t*, size_t);
struct retro_game_info { const char* path; const void* data; size_t size; const char* meta; };
struct retro_system_info { const char* library_name; const char* library_version; const char* valid_extensions; bool need_fullpath; bool block_extract; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { struct retro_game_geometry geometry; struct retro_system_timing timing; };
struct retro_variable { const char* key; const char* value; };
struct retro_controller_description { const char* desc; unsigned id; };
struct retro_controller_info { const struct retro_controller_description* types; unsigned num_types; };
struct retro_disk_control_callback {
    bool (*set_eject_state)(bool); bool (*get_eject_state)(void);
    unsigned (*get_image_index)(void); bool (*set_image_index)(unsigned);
    unsigned (*get_num_images)(void); bool (*replace_image_index)(unsigned, const struct retro_game_info*);
    bool (*add_image_index)(void);
};
static unsigned disk_count(void) { return 2; }
static char lifecycle_path[4096];
static void record(const char* event) {
    if (!lifecycle_path[0]) return;
    FILE* file = fopen(lifecycle_path, "a");
    if (file) { fputs(event, file); fclose(file); }
}
static environment_fn environment;
static video_fn video;
static audio_fn audio;
static uint32_t counter;
static bool input_probe;
static int16_t (*input_state)(unsigned, unsigned, unsigned, unsigned);
static uint8_t input_report[24];
static uint8_t sram[4];
static uint32_t rtc;
static unsigned restore_count;
static unsigned restore_failure;
static const char* memory_mode(void);
static const uint32_t pixels[4] = {0xff00ff00, 0xffff0000, 0xff0000ff, 0xffffffff};
static const int16_t samples[1600] = {0};

void retro_set_environment(environment_fn cb) { environment = cb; }
void retro_set_video_refresh(video_fn cb) { video = cb; }
void retro_set_audio_sample(void (*cb)(int16_t, int16_t)) { (void)cb; }
void retro_set_audio_sample_batch(audio_fn cb) { audio = cb; }
void retro_set_input_poll(void (*cb)(void)) { (void)cb; }
void retro_set_input_state(int16_t (*cb)(unsigned, unsigned, unsigned, unsigned)) { input_state = cb; }
unsigned retro_api_version(void) { return 1; }
void retro_init(void) { lifecycle_path[0] = 0; counter = 0; rtc = 0; restore_count = 0; restore_failure = 0; sram[0] = 1; sram[1] = 2; sram[2] = 3; sram[3] = 4; }
void retro_deinit(void) { record("deinit;"); }
void retro_reset(void) { counter = 0; }
void retro_get_system_info(struct retro_system_info* info) {
    *info = (struct retro_system_info){"Rommio Persistence Test", "1", "fixture", true, false};
}
void retro_get_system_av_info(struct retro_system_av_info* info) {
    *info = (struct retro_system_av_info){{2, 2, 2, 2, 1.0f}, {60.0, 48000.0}};
}
bool retro_load_game(const struct retro_game_info* game) {
    unsigned format = 1;
    struct retro_variable options[] = {
        { "rommio_test_capture", "Capture mode; success|reject|zero|oversize" },
        { "rommio_test_memory", "Memory mode; normal|missing|oversize|empty" }, {NULL, NULL}
    };
    if (!game || !game->path || !environment) return false;
    input_probe = strstr(game->path, "input-probe") != NULL;
    memset(input_report, 0, sizeof(input_report));
    snprintf(lifecycle_path, sizeof(lifecycle_path), "%s.lifecycle", game->path);
    record("load;");
    environment(16, options);
    if (strstr(game->path, "seed") != NULL) {
        static const struct retro_controller_description types[] = {{"Seed controller", 257}};
        static const struct retro_controller_info controllers[] = {{types, 1}, {NULL, 0}};
        static const struct retro_disk_control_callback disks = {.get_num_images = disk_count};
        struct retro_variable extra[] = {{"rommio_seed_only", "Seed-only option; seeded|changed"}, {NULL, NULL}};
        unsigned rotation = 1;
        environment(35, (void*)controllers);
        environment(13, (void*)&disks);
        environment(16, extra);
        environment(1, &rotation);
    }
    if (strstr(game->path, "reject-once") != NULL) restore_failure = 1;
    if (strstr(game->path, "reject-always") != NULL) restore_failure = 2;
    if (strstr(game->path, "fail-load") != NULL) return false;
    return environment(10, &format);
}
bool retro_load_game_special(unsigned type, const struct retro_game_info* game, size_t count) { (void)type; (void)game; (void)count; return false; }
void retro_unload_game(void) { record("unload;"); }
void retro_run(void) {
    counter++; rtc++; sram[0]++;
    if (input_probe && input_state) {
        for (unsigned key = 0; key < 16; key++) input_report[key] = input_state(0, 1, 0, key) != 0;
        for (unsigned stick = 0; stick < 2; stick++) for (unsigned axis = 0; axis < 2; axis++) {
            int16_t value = input_state(0, 5, stick, axis);
            memcpy(input_report + 16 + stick * 4 + axis * 2, &value, 2);
        }
    }
    if (video) video(pixels, 2, 2, 8);
    if (audio) audio(samples, 800);
}
void retro_set_controller_port_device(unsigned port, unsigned device) { (void)port; (void)device; }
void retro_cheat_reset(void) {}
void retro_cheat_set(unsigned index, bool enabled, const char* code) { (void)index; (void)enabled; (void)code; }
unsigned retro_get_region(void) { return 0; }
static const char* memory_mode(void) {
    struct retro_variable setting = {"rommio_test_memory", NULL};
    return environment && environment(15, &setting) && setting.value ? setting.value : "normal";
}
void* retro_get_memory_data(unsigned id) {
    if (input_probe && id == 0) return input_report;
    if (strcmp(memory_mode(), "missing") == 0) return NULL;
    return id == 0 ? (void*)sram : id == 1 ? (void*)&rtc : NULL;
}
size_t retro_get_memory_size(unsigned id) {
    if (input_probe && id == 0) return sizeof(input_report);
    if (id > 1) return 0;
    if (strcmp(memory_mode(), "empty") == 0) return 0;
    if (strcmp(memory_mode(), "oversize") == 0) return (size_t)512 * 1024 * 1024;
    return 4;
}
static const char* mode(void) {
    struct retro_variable setting = {"rommio_test_capture", NULL};
    return environment && environment(15, &setting) && setting.value ? setting.value : "success";
}
size_t retro_serialize_size(void) {
    if (strcmp(mode(), "zero") == 0) return 0;
    if (strcmp(mode(), "oversize") == 0) return (size_t)512 * 1024 * 1024;
    return 8;
}
bool retro_serialize(void* data, size_t size) {
    if (size != 8) return false;
    if (strcmp(mode(), "reject") == 0) { memset(data, 0xa5, size); return false; }
    memcpy(data, &counter, 4); memcpy((uint8_t*)data + 4, sram, 4); return true;
}
bool retro_unserialize(const void* data, size_t size) {
    if (size != 8) return false;
    restore_count++;
    if (restore_failure == 2 || (restore_failure == 1 && restore_count == 1)) {
        counter = 999; rtc = 999; memset(sram, 0xee, sizeof(sram)); return false;
    }
    memcpy(&counter, data, 4); rtc = counter; memcpy(sram, (const uint8_t*)data + 4, 4); return true;
}
