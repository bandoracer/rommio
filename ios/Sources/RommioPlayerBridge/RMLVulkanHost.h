#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

NS_ASSUME_NONNULL_BEGIN

/// Owns the libretro Vulkan context and exports its completed frame to Metal.
/// The bridge calls these methods while holding its core lock.
@interface RMLVulkanHost : NSObject

@property (nonatomic, readonly, nullable) id<MTLTexture> latestTexture;
@property (nonatomic, readonly) NSUInteger width;
@property (nonatomic, readonly) NSUInteger height;

- (BOOL)handleEnvironmentCommand:(unsigned)command data:(void * _Nullable)data;
- (BOOL)activateContext:(NSError * _Nullable * _Nullable)error;
- (BOOL)receiveVideoFrame:(const void * _Nullable)data
                    width:(unsigned)width
                   height:(unsigned)height
                   error:(NSError * _Nullable * _Nullable)error;
- (void)invalidateFrame;
- (void)shutdown;

@end

NS_ASSUME_NONNULL_END
