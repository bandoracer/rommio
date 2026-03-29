#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, RMLLibretroPixelFormat) {
    RMLLibretroPixelFormatUnknown = 0,
    RMLLibretroPixelFormatXRGB8888 = 1,
    RMLLibretroPixelFormatRGB565 = 2,
};

typedef NS_ENUM(NSInteger, RMLInputDevice) {
    RMLInputDeviceJoypad = 1,
    RMLInputDeviceAnalog = 5,
    RMLInputDevicePointer = 6,
};

typedef NS_ENUM(NSInteger, RMLMotionSource) {
    RMLMotionSourceDPad = 0,
    RMLMotionSourceAnalogLeft = 1,
    RMLMotionSourceAnalogRight = 2,
    RMLMotionSourcePointer = 3,
};

@interface RMLVideoFrame : NSObject

@property (nonatomic, readonly) NSData *pixelData;
@property (nonatomic, readonly) NSUInteger width;
@property (nonatomic, readonly) NSUInteger height;
@property (nonatomic, readonly) NSUInteger pitch;
@property (nonatomic, readonly) RMLLibretroPixelFormat pixelFormat;

- (instancetype)initWithPixelData:(NSData *)pixelData
                            width:(NSUInteger)width
                           height:(NSUInteger)height
                            pitch:(NSUInteger)pitch
                      pixelFormat:(RMLLibretroPixelFormat)pixelFormat NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

@interface RMLAudioBatch : NSObject

@property (nonatomic, readonly) NSData *sampleData;
@property (nonatomic, readonly) NSUInteger frameCount;

- (instancetype)initWithSampleData:(NSData *)sampleData
                        frameCount:(NSUInteger)frameCount NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

@interface RMLControllerDescriptor : NSObject

@property (nonatomic, readonly) NSInteger identifier;
@property (nonatomic, readonly) NSString *controllerDescription;

- (instancetype)initWithIdentifier:(NSInteger)identifier
             controllerDescription:(NSString *)controllerDescription NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

@interface RMLLibretroBridge : NSObject

@property (nonatomic, readonly) NSURL *coreURL;
@property (nonatomic, readonly, getter=isPrepared) BOOL prepared;
@property (nonatomic, readonly, getter=isRunning) BOOL running;
@property (nonatomic, readonly) NSDictionary<NSString *, NSString *> *variables;
@property (nonatomic, readonly) double nominalFramesPerSecond;
@property (nonatomic, readonly) double sampleRate;

- (nullable instancetype)initWithCoreURL:(NSURL *)coreURL
                                   error:(NSError * _Nullable * _Nullable)error NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

- (BOOL)prepareGameAtURL:(NSURL *)romURL
         systemDirectory:(NSURL *)systemDirectory
          savesDirectory:(NSURL *)savesDirectory
               variables:(NSDictionary<NSString *, NSString *> *)variables
                   error:(NSError * _Nullable * _Nullable)error;

- (BOOL)runFrame:(NSError * _Nullable * _Nullable)error;
- (nullable RMLVideoFrame *)copyLatestVideoFrame;
- (NSArray<RMLAudioBatch *> *)drainAudioBatches;
- (NSArray<NSNumber *> *)drainRumbleMagnitudes;

- (BOOL)updateVariables:(NSDictionary<NSString *, NSString *> *)variables
                  error:(NSError * _Nullable * _Nullable)error;

- (nullable NSData *)serializeSaveRAM:(NSError * _Nullable * _Nullable)error;
- (BOOL)restoreSaveRAM:(NSData *)saveRAMData
                 error:(NSError * _Nullable * _Nullable)error;
- (nullable NSData *)serializeState:(NSError * _Nullable * _Nullable)error;
- (BOOL)unserializeState:(NSData *)stateData
                   error:(NSError * _Nullable * _Nullable)error;

- (void)setPaused:(BOOL)paused;
- (void)reset;
- (void)setDigitalInputPressed:(BOOL)pressed
                       keyCode:(NSInteger)keyCode
                          port:(NSUInteger)port;
- (void)setMotionSource:(RMLMotionSource)motionSource
                      x:(double)x
                      y:(double)y
                    port:(NSUInteger)port;
- (NSArray<RMLControllerDescriptor *> *)availableControllerDescriptorsForPort:(NSUInteger)port;
- (void)setControllerTypeIdentifier:(NSInteger)identifier
                            forPort:(NSUInteger)port;
- (void)stop;

@end

NS_ASSUME_NONNULL_END
