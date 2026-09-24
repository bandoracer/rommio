#include <mutex>
#include <string>
#include <vector>
#import "RommioPlayerBridge/RMLLibretroBridge.h"
#import "RMLVulkanHost.h"

#import <dlfcn.h>
#import <math.h>
#import <stdatomic.h>
#import <stdarg.h>

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
    retro_game_geometry geometry;
    retro_system_timing timing;
};

struct retro_variable {
    const char *key;
    const char *value;
};

struct retro_rumble_interface {
    bool (*set_rumble_state)(unsigned port, unsigned effect, uint16_t strength);
};

struct retro_log_callback { void (*log)(int level, const char *format, ...); };
static constexpr unsigned RETRO_ENVIRONMENT_GET_LOG_INTERFACE = 27;
static constexpr unsigned RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY = 9;
static constexpr unsigned RETRO_ENVIRONMENT_SET_PIXEL_FORMAT = 10;
static constexpr unsigned RETRO_ENVIRONMENT_GET_VARIABLE = 15;
static constexpr unsigned RETRO_ENVIRONMENT_SET_VARIABLES = 16;
static constexpr unsigned RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE = 17;
static constexpr unsigned RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE = 23;
static constexpr unsigned RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY = 30;
static constexpr unsigned RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY = 31;
static constexpr unsigned RETRO_ENVIRONMENT_GET_USERNAME = 38;

// Cores keep the returned pointer, so the name lives in stable storage.
static std::mutex frontend_username_mutex;
static std::string frontend_username;

static constexpr unsigned RETRO_DEVICE_JOYPAD = 1;
static constexpr unsigned RETRO_DEVICE_ANALOG = 5;
static constexpr unsigned RETRO_DEVICE_POINTER = 6;
static constexpr unsigned RETRO_DEVICE_INDEX_ANALOG_LEFT = 0;
static constexpr unsigned RETRO_DEVICE_INDEX_ANALOG_RIGHT = 1;
static constexpr unsigned RETRO_DEVICE_ID_ANALOG_X = 0;
static constexpr unsigned RETRO_DEVICE_ID_ANALOG_Y = 1;
static constexpr unsigned RETRO_DEVICE_ID_POINTER_X = 0;
static constexpr unsigned RETRO_DEVICE_ID_POINTER_Y = 1;
static constexpr unsigned RETRO_DEVICE_ID_POINTER_PRESSED = 2;
static constexpr unsigned RETRO_MEMORY_SAVE_RAM = 0;

static constexpr unsigned RETRO_PIXEL_FORMAT_0RGB1555 = 0;
static constexpr unsigned RETRO_PIXEL_FORMAT_XRGB8888 = 1;
static constexpr unsigned RETRO_PIXEL_FORMAT_RGB565 = 2;

static void bridge_log_callback(int level, const char *format, ...);
static bool bridge_environment_callback(unsigned cmd, void *data);
static void bridge_video_callback(const void *data, unsigned width, unsigned height, size_t pitch);
static void bridge_audio_sample_callback(int16_t left, int16_t right);
static size_t bridge_audio_sample_batch_callback(const int16_t *data, size_t frames);
static void bridge_input_poll_callback(void);
static int16_t bridge_input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id);
static bool bridge_rumble_callback(unsigned port, unsigned effect, uint16_t strength);

static RMLLibretroBridge *activeBridge = nil;
static std::recursive_mutex coreMutex;

@interface RMLVideoFrame ()

@property (nonatomic, readwrite) NSData *pixelData;
@property (nonatomic, readwrite) NSUInteger width;
@property (nonatomic, readwrite) NSUInteger height;
@property (nonatomic, readwrite) NSUInteger pitch;
@property (nonatomic, readwrite) RMLLibretroPixelFormat pixelFormat;

@end

@implementation RMLVideoFrame

- (instancetype)initWithPixelData:(NSData *)pixelData
                            width:(NSUInteger)width
                           height:(NSUInteger)height
                            pitch:(NSUInteger)pitch
                      pixelFormat:(RMLLibretroPixelFormat)pixelFormat {
    self = [super init];
    if (self != nil) {
        _pixelData = pixelData;
        _width = width;
        _height = height;
        _pitch = pitch;
        _pixelFormat = pixelFormat;
    }
    return self;
}

@end

@interface RMLAudioBatch ()

@property (nonatomic, readwrite) NSData *sampleData;
@property (nonatomic, readwrite) NSUInteger frameCount;

@end

@implementation RMLAudioBatch

- (instancetype)initWithSampleData:(NSData *)sampleData
                        frameCount:(NSUInteger)frameCount {
    self = [super init];
    if (self != nil) {
        _sampleData = sampleData;
        _frameCount = frameCount;
    }
    return self;
}

@end

@interface RMLControllerDescriptor ()

@property (nonatomic, readwrite) NSInteger identifier;
@property (nonatomic, readwrite) NSString *controllerDescription;

@end

@implementation RMLControllerDescriptor

- (instancetype)initWithIdentifier:(NSInteger)identifier
             controllerDescription:(NSString *)controllerDescription {
    self = [super init];
    if (self != nil) {
        _identifier = identifier;
        _controllerDescription = controllerDescription;
    }
    return self;
}

@end

@interface RMLLibretroBridge () {
    void *_libraryHandle;
    const char *(*_persistenceDescriptor)(void);
    bool (*_memoryRestored)(void);
    bool (*_prepareFileRestore)(size_t);
    bool _initialized;
    bool _paused;
    bool _variableUpdatePending;
    retro_environment_t _environmentCallback;
    retro_video_refresh_t _videoCallback;
    retro_audio_sample_t _audioSampleCallback;
    retro_audio_sample_batch_t _audioBatchCallback;
    retro_input_poll_t _inputPollCallback;
    retro_input_state_t _inputStateCallback;

    void (*_retro_set_environment)(retro_environment_t);
    void (*_retro_set_video_refresh)(retro_video_refresh_t);
    void (*_retro_set_audio_sample)(retro_audio_sample_t);
    void (*_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*_retro_set_input_poll)(retro_input_poll_t);
    void (*_retro_set_input_state)(retro_input_state_t);
    void (*_retro_init)(void);
    void (*_retro_deinit)(void);
    unsigned (*_retro_api_version)(void);
    void (*_retro_get_system_info)(retro_system_info *info);
    void (*_retro_get_system_av_info)(retro_system_av_info *info);
    void (*_retro_set_controller_port_device)(unsigned port, unsigned device);
    void (*_retro_reset)(void);
    void (*_retro_run)(void);
    size_t (*_retro_serialize_size)(void);
    bool (*_retro_serialize)(void *data, size_t size);
    bool (*_retro_unserialize)(const void *data, size_t size);
    void *(*_retro_get_memory_data)(unsigned id);
    size_t (*_retro_get_memory_size)(unsigned id);
    bool (*_retro_load_game)(const retro_game_info *game);
    void (*_retro_unload_game)(void);
}

@property (nonatomic, readwrite) NSURL *coreURL;
@property (nonatomic, readwrite, getter=isPrepared) BOOL prepared;
@property (nonatomic, readwrite, getter=isRunning) BOOL running;
@property (nonatomic, readwrite) NSDictionary<NSString *, NSString *> *variables;
@property (nonatomic, readwrite) double nominalFramesPerSecond;
@property (nonatomic, readwrite) double sampleRate;
@property (nonatomic, readwrite) NSURL *systemDirectory;
@property (nonatomic, readwrite) NSURL *savesDirectory;
@property (nonatomic, readwrite) NSURL *romURL;
@property (nonatomic, readwrite) NSData *romData;
@property (nonatomic, readwrite) RMLLibretroPixelFormat pixelFormat;
@property (nonatomic, strong, nullable) RMLVulkanHost *vulkanHost;
@property (nonatomic, copy, nullable) NSString *vulkanFrameError;
@property (nonatomic, readwrite) NSMutableArray<RMLAudioBatch *> *pendingAudioBatches;
@property (nonatomic, readwrite) NSMutableArray<NSNumber *> *pendingRumbleMagnitudes;
@property (nonatomic, readwrite) RMLVideoFrame *latestVideoFrame;
@property (nonatomic, readwrite) NSMutableDictionary<NSNumber *, NSMutableDictionary<NSNumber *, NSNumber *> *> *digitalInputs;
@property (nonatomic) NSMutableDictionary<NSNumber *, NSMutableSet<NSNumber *> *> *pendingDigitalPresses;
@property (nonatomic, copy, nullable) NSString *lastCoreError;
@property (nonatomic, readwrite) NSMutableDictionary<NSNumber *, NSDictionary<NSNumber *, NSNumber *> *> *analogInputs;
@property (nonatomic, readwrite) NSMutableDictionary<NSNumber *, NSDictionary<NSNumber *, NSNumber *> *> *pointerInputs;
@property (nonatomic) NSMutableDictionary<NSNumber *, NSDictionary<NSNumber *, NSNumber *> *> *pendingPointerPresses;

@end

@implementation RMLLibretroBridge

+ (NSString *)frontendUsername {
    std::lock_guard<std::mutex> lock(frontend_username_mutex);
    return frontend_username.empty() ? nil : [NSString stringWithUTF8String:frontend_username.c_str()];
}

+ (void)setFrontendUsername:(NSString *)frontendUsername {
    std::lock_guard<std::mutex> lock(frontend_username_mutex);
    frontend_username = frontendUsername.length > 0 ? std::string(frontendUsername.UTF8String) : std::string();
}

- (nullable instancetype)initWithCoreURL:(NSURL *)coreURL
                                   error:(NSError * _Nullable __autoreleasing *)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    self = [super init];
    if (self == nil) {
        return nil;
    }

    _coreURL = coreURL;
    _variables = @{};
    _pendingAudioBatches = [NSMutableArray array];
    _pendingRumbleMagnitudes = [NSMutableArray array];
    _digitalInputs = [NSMutableDictionary dictionary];
    _pendingDigitalPresses = [NSMutableDictionary dictionary];
    _analogInputs = [NSMutableDictionary dictionary];
    _pointerInputs = [NSMutableDictionary dictionary];
    _pendingPointerPresses = [NSMutableDictionary dictionary];
    _pixelFormat = RMLLibretroPixelFormat0RGB1555;

    if (activeBridge != nil) {
        [self populateError:error code:1110 description:@"Another native session already owns the core."];
        return nil;
    }
    activeBridge = self;
    BOOL loaded = [self loadCore:error];
    activeBridge = nil;
    if (!loaded) { return nil; }
    return self;
}

- (void)dealloc {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    [self stop];
    [self unloadLibrary];
}

- (BOOL)prepareGameAtURL:(NSURL *)romURL
         systemDirectory:(NSURL *)systemDirectory
          savesDirectory:(NSURL *)savesDirectory
               variables:(NSDictionary<NSString *,NSString *> *)variables
                   error:(NSError * _Nullable __autoreleasing *)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    NSLog(@"[RMLLibretroBridge] prepareGame core=%@ rom=%@", self.coreURL.path, romURL.path);
    if (_retro_load_game == nullptr || _retro_get_system_av_info == nullptr) {
        return [self populateError:error
                              code:1001
                       description:@"The libretro core is missing required entry points."];
    }

    if (activeBridge != nil && activeBridge != self) {
        return [self populateError:error code:1110 description:@"Another native session owns the core."];
    }
    [self stop];

    NSMutableDictionary *effective = [self.variables mutableCopy];
    [effective addEntriesFromDictionary:variables];
    self.variables = effective;
    self.systemDirectory = systemDirectory;
    self.savesDirectory = savesDirectory;
    self.romURL = romURL;
    self.latestVideoFrame = nil;
    self.vulkanHost = [effective[@"citra_graphics_api"] isEqualToString:@"Vulkan"]
        ? [[RMLVulkanHost alloc] init] : nil;
    [self.pendingAudioBatches removeAllObjects];
    [self.pendingRumbleMagnitudes removeAllObjects];
    self.pixelFormat = RMLLibretroPixelFormat0RGB1555;
    _variableUpdatePending = false;

    self.lastCoreError = nil;
    self.vulkanFrameError = nil;
    activeBridge = self;
    // Some cores cache directories during retro_set_environment, before init.
    // Install the callback only after this session's owned paths are ready.
    _retro_set_environment(&bridge_environment_callback);
    [self initializeCoreIfNeeded];

    retro_system_info systemInfo = {};
    _retro_get_system_info(&systemInfo);
    self.romData = systemInfo.need_fullpath ? nil : [NSData dataWithContentsOfURL:romURL options:0 error:error];
    if (!systemInfo.need_fullpath && self.romData == nil) { [self stop]; return NO; }
    retro_game_info gameInfo = {
        .path = romURL.fileSystemRepresentation,
        .data = self.romData.bytes,
        .size = self.romData.length,
        .meta = nullptr,
    };

    NSLog(@"[RMLLibretroBridge] calling retro_load_game");
    if (!_retro_load_game(&gameInfo)) {
        [self stop];
        return [self populateError:error
                              code:1002
                       description:self.lastCoreError ?: @"The libretro core rejected the ROM image."];
    }
    NSLog(@"[RMLLibretroBridge] retro_load_game complete");
    if (self.vulkanHost != nil && ![self.vulkanHost activateContext:error]) {
        [self stop];
        return NO;
    }

    retro_system_av_info avInfo = {};
    _retro_get_system_av_info(&avInfo);
    self.nominalFramesPerSecond = avInfo.timing.fps > 0 ? avInfo.timing.fps : 60.0;
    self.sampleRate = avInfo.timing.sample_rate > 0 ? avInfo.timing.sample_rate : 48000.0;
    self.prepared = YES;
    _paused = false;
    return YES;
}

- (BOOL)runFrame:(NSError * _Nullable __autoreleasing *)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (!self.prepared) {
        return [self populateError:error
                              code:1003
                       description:@"Prepare a libretro session before running frames."];
    }

    if (_paused) { return YES; }
    activeBridge = self;
    self.running = YES;
    static atomic_uint_least32_t runCount = 0;
    uint32_t count = atomic_fetch_add(&runCount, 1);
    if (count == 0) {
        NSLog(@"[RMLLibretroBridge] first retro_run");
    }
    _retro_run();
    if (self.vulkanFrameError != nil) {
        return [self populateError:error code:1005 description:self.vulkanFrameError];
    }
    [self.pendingDigitalPresses removeAllObjects];
    [self.pendingPointerPresses removeAllObjects];
    if (count == 0) {
        NSLog(@"[RMLLibretroBridge] first retro_run complete");
    }
    return YES;
}

- (nullable RMLVideoFrame *)copyLatestVideoFrame {
    @synchronized (self) {
        return self.latestVideoFrame;
    }
}

- (nullable id<MTLTexture>)copyLatestHardwareTexture {
    return self.vulkanHost.latestTexture;
}

- (NSArray<RMLAudioBatch *> *)drainAudioBatches {
    @synchronized (self) {
        NSArray<RMLAudioBatch *> *batches = [self.pendingAudioBatches copy];
        [self.pendingAudioBatches removeAllObjects];
        return batches;
    }
}

- (NSArray<NSNumber *> *)drainRumbleMagnitudes {
    @synchronized (self) {
        NSArray<NSNumber *> *events = [self.pendingRumbleMagnitudes copy];
        [self.pendingRumbleMagnitudes removeAllObjects];
        return events;
    }
}

- (BOOL)updateVariables:(NSDictionary<NSString *,NSString *> *)variables
                  error:(NSError * _Nullable __autoreleasing *)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (!self.prepared) {
        return [self populateError:error
                              code:1004
                       description:@"Prepare a libretro session before updating variables."];
    }

    self.variables = variables;
    _variableUpdatePending = true;
    return YES;
}

- (nullable NSData *)serializeSaveRAM:(NSError * _Nullable __autoreleasing *)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (_retro_get_memory_data == nullptr || _retro_get_memory_size == nullptr) {
        [self populateError:error code:1005 description:@"The libretro core does not expose save RAM."];
        return nil;
    }

    void *memory = _retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = _retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (memory == nullptr || size == 0) {
        return [NSData data];
    }
    return [NSData dataWithBytes:memory length:size];
}

- (BOOL)restoreSaveRAM:(NSData *)saveRAMData
                 error:(NSError * _Nullable __autoreleasing *)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (_retro_get_memory_data == nullptr || _retro_get_memory_size == nullptr) {
        return [self populateError:error
                              code:1011
                       description:@"The libretro core does not expose writable save RAM."];
    }
    void *memory = _retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = _retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (memory == nullptr || size == 0) {
        return [self populateError:error
                              code:1012
                       description:@"The libretro core did not expose a save RAM buffer."];
    }
    size_t bytesToCopy = MIN(size, saveRAMData.length);
    memcpy(memory, saveRAMData.bytes, bytesToCopy);
    return YES;
}

- (nullable NSData *)serializeState:(NSError * _Nullable __autoreleasing *)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (_retro_serialize_size == nullptr || _retro_serialize == nullptr) {
        [self populateError:error code:1006 description:@"The libretro core does not support save states."];
        return nil;
    }

    size_t size = _retro_serialize_size();
    if (size == 0 || size > 256 * 1024 * 1024) {
        [self populateError:error code:1007 description:@"The libretro core reported an empty save-state payload."];
        return nil;
    }

    NSMutableData *data = [NSMutableData dataWithLength:size];
    if (!_retro_serialize(data.mutableBytes, size)) {
        [self populateError:error code:1008 description:@"The libretro core failed to serialize state."];
        return nil;
    }
    return data;
}

- (BOOL)unserializeState:(NSData *)stateData
                   error:(NSError * _Nullable __autoreleasing *)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (_retro_unserialize == nullptr) {
        return [self populateError:error
                              code:1009
                       description:@"The libretro core does not support loading save states."];
    }
    [self.vulkanHost invalidateFrame];
    if (!_retro_unserialize(stateData.bytes, stateData.length)) {
        return [self populateError:error
                              code:1010
                       description:@"The libretro core rejected the provided save-state payload."];
    }
    return YES;
}

// The state and every qualified persistent region are read under one native barrier.
- (nullable NSDictionary<NSString *, id> *)captureProgressIncludingState:(BOOL)includeState error:(NSError **)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (!self.prepared || activeBridge != self || _persistenceDescriptor == nullptr || _memoryRestored == nullptr ||
        _retro_get_memory_size == nullptr || _retro_get_memory_data == nullptr) {
        [self populateError:error code:1120 description:@"This core has no qualified persistence interface."]; return nil;
    }
    const char *descriptor = _persistenceDescriptor();
    const bool files = descriptor && (strcmp(descriptor, "rommio-files-v1:ppsspp:psp") == 0 ||
                                      strcmp(descriptor, "rommio-files-v1:azahar:3ds") == 0);
    const bool cartridge = files || (descriptor && strncmp(descriptor, "rommio-cart-v1:", 15) == 0);
    const std::vector<unsigned> regions = cartridge ? std::vector<unsigned>{0u, 1u} : std::vector<unsigned>{0u, 1u, 0x100u, 0x200u, 0x300u};
    NSMutableDictionary *result = [NSMutableDictionary dictionary];
    if (includeState) {
        NSError *stateError = nil;
        NSData *state = [self serializeState:&stateError];
        if (state != nil) { result[@"state"] = state; }
        else { result[@"stateError"] = stateError.localizedDescription ?: @"Snapshot capture failed."; }
    }
    NSMutableDictionary *memory = [NSMutableDictionary dictionary];
    size_t total = 0;
    for (unsigned region : regions) {
        size_t size = _retro_get_memory_size(region); void *data = _retro_get_memory_data(region);
        if (size > 256 * 1024 * 1024 || (size && data == nullptr) || total > 256 * 1024 * 1024 - size) {
            [self populateError:error code:1121 description:@"Invalid persistent memory inventory."]; return nil;
        }
        total += size;
        memory[@(region)] = size ? [NSData dataWithBytes:data length:size] : [NSData data];
    }
    result[@"memory"] = memory;
    result[@"variables"] = [self.variables copy];
    result[@"descriptor"] = descriptor ? [NSString stringWithUTF8String:descriptor] : @"";
    return result;
}

- (BOOL)restoreProgress:(NSDictionary<NSNumber *, NSData *> *)memory state:(nullable NSData *)state error:(NSError **)error {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (!self.prepared || activeBridge != self || _memoryRestored == nullptr || _persistenceDescriptor == nullptr) {
        return [self populateError:error code:1122 description:@"Unsupported restore."];
    }
    const char *descriptor = _persistenceDescriptor();
    const bool files = descriptor && (strcmp(descriptor, "rommio-files-v1:ppsspp:psp") == 0 ||
                                      strcmp(descriptor, "rommio-files-v1:azahar:3ds") == 0);
    const bool cartridge = files || (descriptor && strncmp(descriptor, "rommio-cart-v1:", 15) == 0);
    const std::vector<unsigned> regions = cartridge ? std::vector<unsigned>{0u, 1u} : std::vector<unsigned>{0u, 1u, 0x100u, 0x200u, 0x300u};
    if (memory.count != regions.size()) {
        return [self populateError:error code:1122 description:@"Incomplete or unsupported restore."];
    }
    if (files && (_prepareFileRestore == nullptr || memory[@1].length != 0 || memory[@0].length < 12 ||
                  memory[@0].length > 64u * 1024u * 1024u || !_prepareFileRestore(memory[@0].length))) {
        return [self populateError:error code:1123 description:@"Invalid file-backed game-save restore bundle."];
    }
    // Validate the complete inventory before unserialization can mutate anything.
    for (unsigned region : regions) {
        NSData *bytes = memory[@(region)]; size_t size = _retro_get_memory_size(region);
        if (bytes == nil || bytes.length != size || (size && _retro_get_memory_data(region) == nullptr)) {
            return [self populateError:error code:1123 description:@"Restore memory layout differs from the active cartridge."];
        }
    }
    [self.vulkanHost invalidateFrame];
    if (!files && state != nil && ![self unserializeState:state error:error]) { return NO; }
    for (unsigned region : regions) {
        if (memory[@(region)].length != _retro_get_memory_size(region) ||
            (memory[@(region)].length && _retro_get_memory_data(region) == nullptr)) {
            return [self populateError:error code:1123 description:@"State changed the persistent memory layout."];
        }
    }
    for (unsigned region : regions) {
        NSData *bytes = memory[@(region)];
        if (bytes.length) { memcpy(_retro_get_memory_data(region), bytes.bytes, bytes.length); }
    }
    if (!_memoryRestored()) { return [self populateError:error code:1124 description:@"The core could not apply restored persistent memory."]; }
    // Azahar's file restore unloads and reloads the title. Hardware cores
    // defer the actual boot until context_reset, which is not repeated for a
    // retained Vulkan context. Reboot against the restored file tree before
    // applying the independent resume snapshot.
    if (files && self.vulkanHost != nil && _retro_reset != nullptr) { _retro_reset(); }
    if (files && state != nil && ![self unserializeState:state error:error]) { return NO; }
    [self.pendingAudioBatches removeAllObjects];
    return YES;
}

- (void)setPaused:(BOOL)paused {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    _paused = paused;
    if (paused) {
        [self.analogInputs removeAllObjects];
        [self.pointerInputs removeAllObjects];
        [self.digitalInputs removeAllObjects];
        [self.pendingDigitalPresses removeAllObjects];
        [self.pendingPointerPresses removeAllObjects];
    }
    self.running = !paused;
}

- (void)reset {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (_retro_reset != nullptr) {
        _retro_reset();
    }
}

- (void)setDigitalInputPressed:(BOOL)pressed
                       keyCode:(NSInteger)keyCode
                          port:(NSUInteger)port {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    NSNumber *portKey = @(port);
    NSMutableDictionary<NSNumber *, NSNumber *> *portInputs = self.digitalInputs[portKey];
    if (portInputs == nil) {
        portInputs = [NSMutableDictionary dictionary];
        self.digitalInputs[portKey] = portInputs;
    }
    portInputs[@(keyCode)] = @(pressed);
    // SwiftUI can deliver a short tap's down/up in one run-loop iteration.
    // Preserve the edge until the core has sampled one complete frame.
    if (pressed) {
        NSMutableSet<NSNumber *> *pending = self.pendingDigitalPresses[portKey];
        if (pending == nil) {
            pending = [NSMutableSet set];
            self.pendingDigitalPresses[portKey] = pending;
        }
        [pending addObject:@(keyCode)];
    }
}

- (void)setPointerX:(double)x y:(double)y pressed:(BOOL)pressed port:(NSUInteger)port {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    NSDictionary *values = @{@0: @(x), @1: @(y), @2: @(pressed)};
    self.pointerInputs[@(port)] = values;
    if (pressed) { self.pendingPointerPresses[@(port)] = values; }
}

- (void)setMotionSource:(RMLMotionSource)motionSource
                      x:(double)x
                      y:(double)y
                    port:(NSUInteger)port {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    NSDictionary<NSNumber *, NSNumber *> *values = @{
        @0: @(x),
        @1: @(y),
    };
    switch (motionSource) {
        case RMLMotionSourceAnalogLeft:
            self.analogInputs[@((NSInteger)port * 10 + 0)] = values;
            break;
        case RMLMotionSourceAnalogRight:
            self.analogInputs[@((NSInteger)port * 10 + 1)] = values;
            break;
        case RMLMotionSourcePointer:
            self.pointerInputs[@(port)] = @{
                @0: @(x),
                @1: @(y),
                @2: @(fabs(x) > 0.001 || fabs(y) > 0.001),
            };
            break;
        case RMLMotionSourceDPad: {
            NSNumber *portKey = @(port);
            NSMutableDictionary<NSNumber *, NSNumber *> *portInputs = self.digitalInputs[portKey];
            if (portInputs == nil) {
                portInputs = [NSMutableDictionary dictionary];
                self.digitalInputs[portKey] = portInputs;
            }
            portInputs[@4] = @(y < -0.5);
            portInputs[@5] = @(y > 0.5);
            portInputs[@6] = @(x < -0.5);
            portInputs[@7] = @(x > 0.5);
            break;
        }
    }
}

- (NSArray<RMLControllerDescriptor *> *)availableControllerDescriptorsForPort:(NSUInteger)port {
    (void)port;
    return @[
        [[RMLControllerDescriptor alloc] initWithIdentifier:RETRO_DEVICE_JOYPAD
                                      controllerDescription:@"Standard Gamepad"],
        [[RMLControllerDescriptor alloc] initWithIdentifier:RETRO_DEVICE_ANALOG
                                      controllerDescription:@"Dual Analog"],
    ];
}

- (void)setControllerTypeIdentifier:(NSInteger)identifier
                            forPort:(NSUInteger)port {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    if (_retro_set_controller_port_device != nullptr) {
        _retro_set_controller_port_device((unsigned)port, (unsigned)identifier);
    }
}

- (void)stop {
    std::lock_guard<std::recursive_mutex> guard(coreMutex);
    self.running = NO;
    if (self.prepared && _retro_unload_game != nullptr) {
        _retro_unload_game();
    }
    [self.vulkanHost shutdown];
    self.vulkanHost = nil;
    self.vulkanFrameError = nil;
    self.prepared = NO;
    self.romData = nil;
    [self.digitalInputs removeAllObjects];
    [self.pendingDigitalPresses removeAllObjects];
    [self.pendingPointerPresses removeAllObjects];
    [self.analogInputs removeAllObjects];
    [self.pointerInputs removeAllObjects];
    if (_initialized && _retro_deinit != nullptr) {
        _retro_deinit();
        _initialized = false;
    }
    if (activeBridge == self) {
        activeBridge = nil;
    }
}

#pragma mark - Internal bridge plumbing

- (BOOL)loadCore:(NSError * _Nullable __autoreleasing *)error {
    if (![[NSFileManager defaultManager] fileExistsAtPath:self.coreURL.path]) {
        return [self populateError:error
                              code:1100
                       description:[NSString stringWithFormat:@"Missing libretro core at %@", self.coreURL.path]];
    }

    _libraryHandle = dlopen(self.coreURL.fileSystemRepresentation, RTLD_NOW | RTLD_LOCAL);
    if (_libraryHandle == nullptr) {
        return [self populateError:error
                              code:1101
                       description:[NSString stringWithUTF8String:dlerror() ?: "Unable to load libretro core."]];
    }

    _retro_set_environment = (void (*)(retro_environment_t))dlsym(_libraryHandle, "retro_set_environment");
    _retro_set_video_refresh = (void (*)(retro_video_refresh_t))dlsym(_libraryHandle, "retro_set_video_refresh");
    _retro_set_audio_sample = (void (*)(retro_audio_sample_t))dlsym(_libraryHandle, "retro_set_audio_sample");
    _retro_set_audio_sample_batch = (void (*)(retro_audio_sample_batch_t))dlsym(_libraryHandle, "retro_set_audio_sample_batch");
    _retro_set_input_poll = (void (*)(retro_input_poll_t))dlsym(_libraryHandle, "retro_set_input_poll");
    _retro_set_input_state = (void (*)(retro_input_state_t))dlsym(_libraryHandle, "retro_set_input_state");
    _retro_init = (void (*)(void))dlsym(_libraryHandle, "retro_init");
    _retro_deinit = (void (*)(void))dlsym(_libraryHandle, "retro_deinit");
    _retro_api_version = (unsigned (*)(void))dlsym(_libraryHandle, "retro_api_version");
    _retro_get_system_info = (void (*)(retro_system_info *))dlsym(_libraryHandle, "retro_get_system_info");
    _retro_get_system_av_info = (void (*)(retro_system_av_info *))dlsym(_libraryHandle, "retro_get_system_av_info");
    _retro_set_controller_port_device = (void (*)(unsigned, unsigned))dlsym(_libraryHandle, "retro_set_controller_port_device");
    _retro_reset = (void (*)(void))dlsym(_libraryHandle, "retro_reset");
    _retro_run = (void (*)(void))dlsym(_libraryHandle, "retro_run");
    _retro_serialize_size = (size_t (*)(void))dlsym(_libraryHandle, "retro_serialize_size");
    _retro_serialize = (bool (*)(void *, size_t))dlsym(_libraryHandle, "retro_serialize");
    _retro_unserialize = (bool (*)(const void *, size_t))dlsym(_libraryHandle, "retro_unserialize");
    _retro_get_memory_data = (void *(*)(unsigned))dlsym(_libraryHandle, "retro_get_memory_data");
    _retro_get_memory_size = (size_t (*)(unsigned))dlsym(_libraryHandle, "retro_get_memory_size");
    _retro_load_game = (bool (*)(const retro_game_info *))dlsym(_libraryHandle, "retro_load_game");
    _retro_unload_game = (void (*)(void))dlsym(_libraryHandle, "retro_unload_game");

    _persistenceDescriptor = (const char *(*)(void))dlsym(_libraryHandle, "retro_rommio_persistence_descriptor");
    _memoryRestored = (bool (*)(void))dlsym(_libraryHandle, "retro_rommio_memory_restored");
    _prepareFileRestore = (bool (*)(size_t))dlsym(_libraryHandle, "retro_rommio_prepare_restore");
    if (_retro_set_environment == nullptr || _retro_init == nullptr || _retro_run == nullptr ||
        _retro_set_video_refresh == nullptr || _retro_set_audio_sample == nullptr || _retro_set_audio_sample_batch == nullptr ||
        _retro_set_input_poll == nullptr || _retro_set_input_state == nullptr || _retro_get_system_info == nullptr) {
        return [self populateError:error
                              code:1102
                       description:@"The dynamic library does not export the required libretro entry points."];
    }

    _retro_set_video_refresh(&bridge_video_callback);
    _retro_set_audio_sample(&bridge_audio_sample_callback);
    _retro_set_audio_sample_batch(&bridge_audio_sample_batch_callback);
    _retro_set_input_poll(&bridge_input_poll_callback);
    _retro_set_input_state(&bridge_input_state_callback);
    return YES;
}

- (void)initializeCoreIfNeeded {
    if (_initialized) {
        return;
    }
    _retro_init();
    _initialized = true;
}

- (void)unloadLibrary {
    if (_libraryHandle != nullptr) {
        dlclose(_libraryHandle);
        _libraryHandle = nullptr;
    }
}

- (void)storeVideoFrameBytes:(const void *)data
                       width:(unsigned)width
                      height:(unsigned)height
                       pitch:(size_t)pitch {
    if (self.vulkanHost != nil) {
        NSError *frameError = nil;
        if (![self.vulkanHost receiveVideoFrame:data width:width height:height error:&frameError]) {
            self.vulkanFrameError = frameError.localizedDescription;
        }
        return;
    }
    if (data == nullptr || width == 0 || height == 0 || pitch == 0) {
        return;
    }
    const size_t bytesPerPixel = self.pixelFormat == RMLLibretroPixelFormatXRGB8888 ? 4 : 2;
    if (self.pixelFormat == RMLLibretroPixelFormatUnknown ||
        width > SIZE_MAX / bytesPerPixel || pitch < width * bytesPerPixel || height > SIZE_MAX / pitch) {
        return;
    }
    NSData *pixelData = [NSData dataWithBytes:data length:(NSUInteger)(pitch * height)];
    RMLVideoFrame *frame = [[RMLVideoFrame alloc] initWithPixelData:pixelData
                                                              width:width
                                                             height:height
                                                              pitch:pitch
                                                        pixelFormat:self.pixelFormat];
    @synchronized (self) {
        self.latestVideoFrame = frame;
    }
}

- (void)appendAudioBatchSamples:(const int16_t *)samples
                          frames:(size_t)frames {
    if (samples == nullptr || frames == 0) {
        return;
    }
    NSData *sampleData = [NSData dataWithBytes:samples length:frames * sizeof(int16_t) * 2];
    RMLAudioBatch *batch = [[RMLAudioBatch alloc] initWithSampleData:sampleData frameCount:frames];
    @synchronized (self) {
        [self.pendingAudioBatches addObject:batch];
    }
}

- (BOOL)handleEnvironmentCommand:(unsigned)cmd
                            data:(void *)data {
    if (self.vulkanHost != nil && [self.vulkanHost handleEnvironmentCommand:cmd data:data]) {
        return true;
    }
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            if (data == nullptr) { return false; }
            static_cast<retro_log_callback *>(data)->log = &bridge_log_callback;
            return true;
        }
        case 3: { // RETRO_ENVIRONMENT_GET_CAN_DUPE
            if (data == nullptr) { return false; }
            // A null video callback keeps latestVideoFrame unchanged.
            *static_cast<bool *>(data) = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: {
            const char **path = (const char **)data;
            *path = self.systemDirectory.fileSystemRepresentation;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_USERNAME: {
            if (data == nullptr) { return false; }
            std::lock_guard<std::mutex> lock(frontend_username_mutex);
            if (frontend_username.empty()) { return false; }
            *static_cast<const char **>(data) = frontend_username.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            const char **path = (const char **)data;
            *path = self.savesDirectory.fileSystemRepresentation;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (data == nullptr) {
                return false;
            }
            unsigned format = *(unsigned *)data;
            switch (format) {
                case RETRO_PIXEL_FORMAT_XRGB8888:
                    self.pixelFormat = RMLLibretroPixelFormatXRGB8888;
                    return true;
                case RETRO_PIXEL_FORMAT_RGB565:
                    self.pixelFormat = RMLLibretroPixelFormatRGB565;
                    return true;
                case RETRO_PIXEL_FORMAT_0RGB1555:
                    self.pixelFormat = RMLLibretroPixelFormat0RGB1555;
                    return true;
                default:
                    return false;
            }
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (data == nullptr) {
                return false;
            }
            retro_variable *variable = (retro_variable *)data;
            NSString *key = variable->key != nullptr ? [NSString stringWithUTF8String:variable->key] : nil;
            NSString *value = key != nil ? self.variables[key] : nil;
            variable->value = value != nil ? [value UTF8String] : nullptr;
            return value != nil;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            NSMutableDictionary *values = [self.variables mutableCopy];
            for (const retro_variable *v = (const retro_variable *)data; v != nullptr && v->key != nullptr; ++v) {
                NSString *key = [NSString stringWithUTF8String:v->key];
                NSString *definition = v->value ? [NSString stringWithUTF8String:v->value] : @"";
                NSArray *parts = [definition componentsSeparatedByString:@"; "];
                if (values[key] == nil && parts.count == 2) { values[key] = [[parts[1] componentsSeparatedByString:@"|"] firstObject]; }
            }
            self.variables = values;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            if (data == nullptr) {
                return false;
            }
            bool *flag = (bool *)data;
            *flag = _variableUpdatePending;
            _variableUpdatePending = false;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
            if (data == nullptr) {
                return false;
            }
            retro_rumble_interface *rumble = (retro_rumble_interface *)data;
            rumble->set_rumble_state = &bridge_rumble_callback;
            return true;
        }
        default:
            return false;
    }
}

- (int16_t)inputStateForPort:(unsigned)port
                      device:(unsigned)device
                       index:(unsigned)index
                          id:(unsigned)identifier {
    switch (device) {
        case RETRO_DEVICE_JOYPAD: {
            NSNumber *value = self.digitalInputs[@(port)][@(identifier)];
            return (value.boolValue || [self.pendingDigitalPresses[@(port)] containsObject:@(identifier)]) ? 1 : 0;
        }
        case RETRO_DEVICE_ANALOG: {
            NSNumber *key = @((NSInteger)port * 10 + (NSInteger)index);
            NSDictionary<NSNumber *, NSNumber *> *values = self.analogInputs[key];
            double coordinate = [values[@(identifier)] doubleValue];
            return (int16_t)lrint(MAX(-1.0, MIN(1.0, coordinate)) * 32767.0);
        }
        case RETRO_DEVICE_POINTER: {
            NSDictionary<NSNumber *, NSNumber *> *values = self.pendingPointerPresses[@(port)] ?: self.pointerInputs[@(port)];
            if (identifier == RETRO_DEVICE_ID_POINTER_PRESSED) {
                return [values[@(identifier)] boolValue] ? 1 : 0;
            }
            double coordinate = [values[@(identifier)] doubleValue];
            return (int16_t)lrint(MAX(-1.0, MIN(1.0, coordinate)) * 32767.0);
        }
        default:
            return 0;
    }
}

- (void)recordRumbleMagnitude:(uint16_t)strength {
    @synchronized (self) {
        [self.pendingRumbleMagnitudes addObject:@((double)strength / (double)UINT16_MAX)];
    }
}

- (BOOL)populateError:(NSError * _Nullable __autoreleasing *)error
                 code:(NSInteger)code
          description:(NSString *)description {
    if (error != NULL) {
        *error = [NSError errorWithDomain:@"RommioPlayerBridge"
                                     code:code
                                 userInfo:@{ NSLocalizedDescriptionKey: description }];
    }
    return NO;
}

@end

static void bridge_log_callback(int level, const char *format, ...) {
    if (format == nullptr) { return; }
    va_list arguments;
    va_start(arguments, format);
    NSString *message = [[NSString alloc] initWithFormat:[NSString stringWithUTF8String:format] arguments:arguments];
    va_end(arguments);
    // Keep warnings/errors useful without emitting per-frame debug traffic.
    if (level >= 2) { NSLog(@"[LibretroCore] %@", message); }
    if (level >= 3) { activeBridge.lastCoreError = [message stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet]; }
}

static bool bridge_environment_callback(unsigned cmd, void *data) {
    return activeBridge != nil ? [activeBridge handleEnvironmentCommand:cmd data:data] : false;
}

static void bridge_video_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
    [activeBridge storeVideoFrameBytes:data width:width height:height pitch:pitch];
}

static void bridge_audio_sample_callback(int16_t left, int16_t right) {
    int16_t stereo[2] = { left, right };
    [activeBridge appendAudioBatchSamples:stereo frames:1];
}

static size_t bridge_audio_sample_batch_callback(const int16_t *data, size_t frames) {
    [activeBridge appendAudioBatchSamples:data frames:frames];
    return frames;
}

static void bridge_input_poll_callback(void) {}

static int16_t bridge_input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
    return activeBridge != nil ? [activeBridge inputStateForPort:port device:device index:index id:id] : 0;
}

static bool bridge_rumble_callback(unsigned port, unsigned effect, uint16_t strength) {
    (void)port;
    (void)effect;
    [activeBridge recordRumbleMagnitude:strength];
    return true;
}
