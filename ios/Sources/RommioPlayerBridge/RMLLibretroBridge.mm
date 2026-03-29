#import "RommioPlayerBridge/RMLLibretroBridge.h"

#import <dlfcn.h>
#import <math.h>
#import <stdatomic.h>

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

static constexpr unsigned RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY = 9;
static constexpr unsigned RETRO_ENVIRONMENT_SET_PIXEL_FORMAT = 10;
static constexpr unsigned RETRO_ENVIRONMENT_GET_VARIABLE = 15;
static constexpr unsigned RETRO_ENVIRONMENT_SET_VARIABLES = 16;
static constexpr unsigned RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE = 17;
static constexpr unsigned RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE = 23;
static constexpr unsigned RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY = 30;
static constexpr unsigned RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY = 31;

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

static bool bridge_environment_callback(unsigned cmd, void *data);
static void bridge_video_callback(const void *data, unsigned width, unsigned height, size_t pitch);
static void bridge_audio_sample_callback(int16_t left, int16_t right);
static size_t bridge_audio_sample_batch_callback(const int16_t *data, size_t frames);
static void bridge_input_poll_callback(void);
static int16_t bridge_input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id);
static bool bridge_rumble_callback(unsigned port, unsigned effect, uint16_t strength);

static RMLLibretroBridge *activeBridge = nil;

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
    bool _initialized;
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
@property (nonatomic, readwrite) RMLLibretroPixelFormat pixelFormat;
@property (nonatomic, readwrite) NSMutableArray<RMLAudioBatch *> *pendingAudioBatches;
@property (nonatomic, readwrite) NSMutableArray<NSNumber *> *pendingRumbleMagnitudes;
@property (nonatomic, readwrite) RMLVideoFrame *latestVideoFrame;
@property (nonatomic, readwrite) NSMutableDictionary<NSNumber *, NSMutableDictionary<NSNumber *, NSNumber *> *> *digitalInputs;
@property (nonatomic, readwrite) NSMutableDictionary<NSNumber *, NSDictionary<NSNumber *, NSNumber *> *> *analogInputs;
@property (nonatomic, readwrite) NSMutableDictionary<NSNumber *, NSDictionary<NSNumber *, NSNumber *> *> *pointerInputs;

@end

@implementation RMLLibretroBridge

- (nullable instancetype)initWithCoreURL:(NSURL *)coreURL
                                   error:(NSError * _Nullable __autoreleasing *)error {
    self = [super init];
    if (self == nil) {
        return nil;
    }

    _coreURL = coreURL;
    _variables = @{};
    _pendingAudioBatches = [NSMutableArray array];
    _pendingRumbleMagnitudes = [NSMutableArray array];
    _digitalInputs = [NSMutableDictionary dictionary];
    _analogInputs = [NSMutableDictionary dictionary];
    _pointerInputs = [NSMutableDictionary dictionary];
    _pixelFormat = RMLLibretroPixelFormatXRGB8888;

    if (![self loadCore:error]) {
        return nil;
    }
    return self;
}

- (void)dealloc {
    [self stop];
    [self unloadLibrary];
}

- (BOOL)prepareGameAtURL:(NSURL *)romURL
         systemDirectory:(NSURL *)systemDirectory
          savesDirectory:(NSURL *)savesDirectory
               variables:(NSDictionary<NSString *,NSString *> *)variables
                   error:(NSError * _Nullable __autoreleasing *)error {
    NSLog(@"[RMLLibretroBridge] prepareGame core=%@ rom=%@", self.coreURL.path, romURL.path);
    if (_retro_load_game == nullptr || _retro_get_system_av_info == nullptr) {
        return [self populateError:error
                              code:1001
                       description:@"The libretro core is missing required entry points."];
    }

    [self stop];

    self.variables = variables;
    self.systemDirectory = systemDirectory;
    self.savesDirectory = savesDirectory;
    self.romURL = romURL;
    self.latestVideoFrame = nil;
    [self.pendingAudioBatches removeAllObjects];
    [self.pendingRumbleMagnitudes removeAllObjects];
    self.pixelFormat = RMLLibretroPixelFormatXRGB8888;
    _variableUpdatePending = false;

    activeBridge = self;
    [self initializeCoreIfNeeded];

    retro_game_info gameInfo = {
        .path = romURL.fileSystemRepresentation,
        .data = nullptr,
        .size = 0,
        .meta = nullptr,
    };

    NSLog(@"[RMLLibretroBridge] calling retro_load_game");
    if (!_retro_load_game(&gameInfo)) {
        return [self populateError:error
                              code:1002
                       description:@"The libretro core rejected the ROM image."];
    }
    NSLog(@"[RMLLibretroBridge] retro_load_game complete");

    retro_system_av_info avInfo = {};
    _retro_get_system_av_info(&avInfo);
    self.nominalFramesPerSecond = avInfo.timing.fps > 0 ? avInfo.timing.fps : 60.0;
    self.sampleRate = avInfo.timing.sample_rate > 0 ? avInfo.timing.sample_rate : 48000.0;
    self.prepared = YES;
    return YES;
}

- (BOOL)runFrame:(NSError * _Nullable __autoreleasing *)error {
    if (!self.prepared) {
        return [self populateError:error
                              code:1003
                       description:@"Prepare a libretro session before running frames."];
    }

    activeBridge = self;
    self.running = YES;
    static atomic_uint_least32_t runCount = 0;
    uint32_t count = atomic_fetch_add(&runCount, 1);
    if (count == 0) {
        NSLog(@"[RMLLibretroBridge] first retro_run");
    }
    _retro_run();
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
    if (_retro_serialize_size == nullptr || _retro_serialize == nullptr) {
        [self populateError:error code:1006 description:@"The libretro core does not support save states."];
        return nil;
    }

    size_t size = _retro_serialize_size();
    if (size == 0) {
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
    if (_retro_unserialize == nullptr) {
        return [self populateError:error
                              code:1009
                       description:@"The libretro core does not support loading save states."];
    }
    if (!_retro_unserialize(stateData.bytes, stateData.length)) {
        return [self populateError:error
                              code:1010
                       description:@"The libretro core rejected the provided save-state payload."];
    }
    return YES;
}

- (void)setPaused:(BOOL)paused {
    self.running = !paused;
}

- (void)reset {
    if (_retro_reset != nullptr) {
        _retro_reset();
    }
}

- (void)setDigitalInputPressed:(BOOL)pressed
                       keyCode:(NSInteger)keyCode
                          port:(NSUInteger)port {
    NSNumber *portKey = @(port);
    NSMutableDictionary<NSNumber *, NSNumber *> *portInputs = self.digitalInputs[portKey];
    if (portInputs == nil) {
        portInputs = [NSMutableDictionary dictionary];
        self.digitalInputs[portKey] = portInputs;
    }
    portInputs[@(keyCode)] = @(pressed);
}

- (void)setMotionSource:(RMLMotionSource)motionSource
                      x:(double)x
                      y:(double)y
                    port:(NSUInteger)port {
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
            portInputs[@0] = @(y < -0.5);
            portInputs[@1] = @(y > 0.5);
            portInputs[@2] = @(x < -0.5);
            portInputs[@3] = @(x > 0.5);
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
    if (_retro_set_controller_port_device != nullptr) {
        _retro_set_controller_port_device((unsigned)port, (unsigned)identifier);
    }
}

- (void)stop {
    self.running = NO;
    if (self.prepared && _retro_unload_game != nullptr) {
        _retro_unload_game();
    }
    self.prepared = NO;
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

    if (_retro_set_environment == nullptr || _retro_init == nullptr || _retro_run == nullptr) {
        return [self populateError:error
                              code:1102
                       description:@"The dynamic library does not export the required libretro entry points."];
    }

    _retro_set_environment(&bridge_environment_callback);
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
    if (data == nullptr || width == 0 || height == 0 || pitch == 0) {
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
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: {
            const char **path = (const char **)data;
            *path = self.systemDirectory.fileSystemRepresentation;
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
                default:
                    self.pixelFormat = RMLLibretroPixelFormatUnknown;
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
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return true;
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
            return value.boolValue ? 1 : 0;
        }
        case RETRO_DEVICE_ANALOG: {
            NSNumber *key = @((NSInteger)port * 10 + (NSInteger)index);
            NSDictionary<NSNumber *, NSNumber *> *values = self.analogInputs[key];
            double coordinate = [values[@(identifier)] doubleValue];
            return (int16_t)lrint(MAX(-1.0, MIN(1.0, coordinate)) * 32767.0);
        }
        case RETRO_DEVICE_POINTER: {
            NSDictionary<NSNumber *, NSNumber *> *values = self.pointerInputs[@(port)];
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
