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
static uint8_t g_save_ram[4] = {1, 2, 3, 4};
static uint8_t g_state_buffer[8] = {9, 8, 7, 6, 5, 4, 3, 2};

void retro_set_environment(retro_environment_t callback) { g_environment = callback; }
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

    if (g_video) {
        g_video(g_framebuffer, 2, 2, sizeof(uint32_t) * 2);
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
    if (!game || !game->path) {
        return false;
    }

    if (g_environment) {
        unsigned pixel_format = 1;
        g_environment(10, &pixel_format);
    }
    return true;
}

void retro_unload_game(void) {}
