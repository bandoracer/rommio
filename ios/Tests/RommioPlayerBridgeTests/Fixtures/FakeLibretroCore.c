#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};

struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};

struct retro_system_timing {
    double fps;
    double sample_rate;
};

struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};

struct retro_variable {
    const char *key;
    const char *value;
};

struct retro_rumble_interface {
    bool (*set_rumble_state)(unsigned port, unsigned effect, uint16_t strength);
};

static retro_environment_t g_environment = 0;
static retro_video_refresh_t g_video = 0;
static retro_audio_sample_batch_t g_audio_batch = 0;
static retro_input_poll_t g_input_poll = 0;
static retro_input_state_t g_input_state = 0;

static uint32_t g_framebuffer[4];
static uint16_t g_framebuffer16[6];
static unsigned g_pixel_format = 1;
static uint8_t g_save_ram[4] = {1, 2, 3, 4};
static uint8_t g_state_buffer[8] = {9, 8, 7, 6, 5, 4, 3, 2};

static bool g_directories_ready = false;
void retro_set_environment(retro_environment_t callback) {
    g_environment = callback;
    // melonDS caches these during callback registration, before retro_init.
    const char *system = 0, *saves = 0;
    g_directories_ready = callback(9, &system) && system && system[0]
        && callback(31, &saves) && saves && saves[0];
}
void retro_set_video_refresh(retro_video_refresh_t callback) { g_video = callback; }
void retro_set_audio_sample(retro_audio_sample_t callback) { (void)callback; }
void retro_set_audio_sample_batch(retro_audio_sample_batch_t callback) { g_audio_batch = callback; }
void retro_set_input_poll(retro_input_poll_t callback) { g_input_poll = callback; }
void retro_set_input_state(retro_input_state_t callback) { g_input_state = callback; }

void retro_init(void) {}
void retro_deinit(void) {}
unsigned retro_api_version(void) { return 1; }

void retro_get_system_info(struct retro_system_info *info) {
    info->library_name = "Fake Core";
    info->library_version = "1.0";
    info->valid_extensions = "nes";
    info->need_fullpath = true;
    info->block_extract = false;
}

void retro_get_system_av_info(struct retro_system_av_info *info) {
    info->geometry.base_width = 2;
    info->geometry.base_height = 2;
    info->geometry.max_width = 2;
    info->geometry.max_height = 2;
    info->geometry.aspect_ratio = 1.0f;
    info->timing.fps = 60.0;
    info->timing.sample_rate = 48000.0;
}

void retro_set_controller_port_device(unsigned port, unsigned device) {
    (void)port;
    (void)device;
}

void retro_reset(void) {
    memset(g_state_buffer, 0, sizeof(g_state_buffer));
}

void retro_run(void) {
    if (g_input_poll) {
        g_input_poll();
    }

    bool wants_green = false;
    if (g_environment) {
        struct retro_variable variable = {"faux_palette", 0};
        if (g_environment(15, &variable) && variable.value && strcmp(variable.value, "green") == 0) {
            wants_green = true;
        }
    }

    int16_t pressed = 0;
    if (g_input_state) {
        pressed = g_input_state(0, 1, 0, 0);
    }

    uint32_t color = wants_green ? 0xFF00FF00u : (pressed ? 0xFFFF00FFu : 0xFFFF0000u);
    for (size_t index = 0; index < 4; index += 1) {
        g_framebuffer[index] = color;
    }

    struct retro_variable input_test = {"faux_input", 0};
    if (g_environment(15, &input_test) && input_test.value && strcmp(input_test.value, "true") == 0) {
        g_framebuffer[0] = 0;
        for (unsigned key = 0; key < 16; key++) if (g_input_state(0, 1, 0, key)) g_framebuffer[0] |= 1u << key;
        g_framebuffer[1] = (uint16_t)g_input_state(0, 5, 0, 0) | ((uint32_t)(uint16_t)g_input_state(0, 5, 0, 1) << 16);
        g_framebuffer[2] = (uint16_t)g_input_state(0, 5, 1, 0) | ((uint32_t)(uint16_t)g_input_state(0, 5, 1, 1) << 16);
    }
    struct retro_variable pointer_test = {"faux_pointer", 0};
    if (g_environment && g_environment(15, &pointer_test) && pointer_test.value && g_input_state) {
        g_framebuffer[1] = (uint32_t)(int32_t)g_input_state(0, 6, 0, 0);
        g_framebuffer[2] = (uint32_t)(int32_t)g_input_state(0, 6, 0, 1);
        g_framebuffer[3] = g_input_state(0, 6, 0, 2);
    }

    if (g_video) {
        if (g_pixel_format == 1) {
            g_video(g_framebuffer, 2, 2, sizeof(uint32_t) * 2);
        } else {
            g_framebuffer16[0] = g_pixel_format == 2 ? 0xf800 : 0x7c00;
            g_framebuffer16[1] = g_pixel_format == 2 ? 0x07e0 : 0x03e0;
            g_framebuffer16[2] = 0xffff; // row padding
            g_framebuffer16[3] = 0x001f;
            g_framebuffer16[4] = g_pixel_format == 2 ? 0xffff : 0x7fff;
            g_framebuffer16[5] = 0xffff;
            g_video(g_framebuffer16, 2, 2, sizeof(uint16_t) * 3);
        }
    }

    if (g_audio_batch) {
        int16_t samples[4] = {100, -100, 200, -200};
        g_audio_batch(samples, 2);
    }

    if (g_environment) {
        struct retro_rumble_interface rumble = {0};
        if (g_environment(23, &rumble) && rumble.set_rumble_state) {
            rumble.set_rumble_state(0, 0, 1234);
        }
    }
}

size_t retro_serialize_size(void) {
    return sizeof(g_state_buffer);
}

bool retro_serialize(void *data, size_t size) {
    if (size < sizeof(g_state_buffer)) {
        return false;
    }
    memcpy(data, g_state_buffer, sizeof(g_state_buffer));
    return true;
}

bool retro_unserialize(const void *data, size_t size) {
    if (size < sizeof(g_state_buffer)) {
        return false;
    }
    memcpy(g_state_buffer, data, sizeof(g_state_buffer));
    return true;
}

void *retro_get_memory_data(unsigned id) {
    return id == 0 ? g_save_ram : 0;
}

size_t retro_get_memory_size(unsigned id) {
    return id == 0 ? sizeof(g_save_ram) : 0;
}

bool retro_load_game(const struct retro_game_info *game) {
    if (!g_directories_ready) return false;
    bool can_dupe = false;
    if (!g_environment || !g_environment(3, &can_dupe) || !can_dupe) return false;
    if (!game || !game->path) {
        return false;
    }

    if (g_environment) {
        struct retro_variable variable = {"faux_pixel_format", 0};
        g_pixel_format = 1;
        if (g_environment(15, &variable) && variable.value) {
            if (strcmp(variable.value, "rgb565") == 0) g_pixel_format = 2;
            if (strcmp(variable.value, "0rgb1555") == 0) g_pixel_format = 0;
            if (strcmp(variable.value, "default1555") == 0) { g_pixel_format = 0; return true; }
            if (strcmp(variable.value, "invalid") == 0) {
                unsigned unsupported = 99;
                g_pixel_format = 0;
                return !g_environment(10, &unsupported);
            }
        }
        if (!g_environment(10, &g_pixel_format)) return false;
    }
    return true;
}

void retro_unload_game(void) {}
