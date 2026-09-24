#import "RMLVulkanHost.h"

#import <QuartzCore/QuartzCore.h>
#import <mutex>
#import <vector>

#define VK_USE_PLATFORM_METAL_EXT 1
#include <libretro_vulkan.h>

static NSError *RMLVulkanError(NSString *message) {
    return [NSError errorWithDomain:@"Rommio.VulkanHost" code:1
                           userInfo:@{NSLocalizedDescriptionKey: message}];
}

@interface RMLVulkanHost () {
@public
    retro_hw_render_callback _renderCallback;
    retro_hw_render_context_negotiation_interface_vulkan _negotiation;
    retro_hw_render_interface_vulkan _interface;
    VkInstance _instance;
    VkPhysicalDevice _physicalDevice;
    VkDevice _device;
    VkQueue _queue;
    unsigned _queueFamily;
    retro_vulkan_image _pendingImage;
    bool _hasRenderCallback;
    bool _hasNegotiation;
    bool _hasPendingImage;
    bool _contextActive;
    std::mutex _queueLock;
}
@property (nonatomic, readwrite, nullable) id<MTLTexture> latestTexture;
@property (nonatomic, readwrite) NSUInteger width;
@property (nonatomic, readwrite) NSUInteger height;
@property (nonatomic, copy, nullable) NSString *submissionError;
@end

static RMLVulkanHost *RMLHost(void *handle) {
    return (__bridge RMLVulkanHost *)handle;
}

static void RMLSetImage(void *handle, const retro_vulkan_image *image,
                        uint32_t count, const VkSemaphore *semaphores,
                        uint32_t queueFamily) {
    RMLVulkanHost *host = RMLHost(handle);
    if (count != 0 || semaphores != nullptr || queueFamily != host->_queueFamily) {
        host.submissionError = @"The core submitted an unsupported Vulkan queue or semaphore contract.";
        return;
    }
    host->_hasPendingImage = image != nullptr;
    if (image) host->_pendingImage = *image;
}

static uint32_t RMLGetSyncIndex(void *handle) {
    (void)handle;
    return 0;
}

static uint32_t RMLGetSyncIndexMask(void *handle) {
    (void)handle;
    return 1;
}

static void RMLWaitSyncIndex(void *handle) {
    RMLVulkanHost *host = RMLHost(handle);
    if (!host->_queue) return;
    std::lock_guard<std::mutex> lock(host->_queueLock);
    vkQueueWaitIdle(host->_queue);
}

static void RMLLockQueue(void *handle) {
    RMLHost(handle)->_queueLock.lock();
}

static void RMLUnlockQueue(void *handle) {
    RMLHost(handle)->_queueLock.unlock();
}

@implementation RMLVulkanHost

- (BOOL)handleEnvironmentCommand:(unsigned)command data:(void *)data {
    if (!data) return NO;
    switch (command) {
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            *(retro_hw_context_type *)data = RETRO_HW_CONTEXT_VULKAN;
            return YES;
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            const auto *callback = (const retro_hw_render_callback *)data;
            if (callback->context_type != RETRO_HW_CONTEXT_VULKAN) return NO;
            _renderCallback = *callback;
            _hasRenderCallback = true;
            return YES;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
            // Azahar passes the interface struct itself (cast to void **), as
            // specified by libretro's environment callback contract.
            const auto *negotiation =
                (const retro_hw_render_context_negotiation_interface_vulkan *)data;
            if (!negotiation ||
                negotiation->interface_type != RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN ||
                negotiation->interface_version < 1) return NO;
            _negotiation = *negotiation;
            _hasNegotiation = true;
            return YES;
        }
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
            if (!_contextActive) return NO;
            *(const retro_hw_render_interface_vulkan **)data = &_interface;
            return YES;
        case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
            auto *support = (retro_hw_render_context_negotiation_interface *)data;
            if (support->interface_type != RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN)
                return NO;
            support->interface_version = 1;
            return YES;
        }
        default:
            return NO;
    }
}

- (BOOL)activateContext:(NSError **)error {
    if (!_hasRenderCallback || !_hasNegotiation) {
        if (error) *error = RMLVulkanError(@"Azahar did not negotiate a Vulkan render context.");
        return NO;
    }
    if (_contextActive) return YES;

    const VkApplicationInfo fallbackApp = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Rommio",
        .apiVersion = VK_API_VERSION_1_1,
    };
    const VkApplicationInfo *app = _negotiation.get_application_info
        ? _negotiation.get_application_info() : &fallbackApp;
    uint32_t extensionCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount, nullptr);
    std::vector<VkExtensionProperties> availableExtensions(extensionCount);
    vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount, availableExtensions.data());
    bool hasPortability = false;
    for (const auto &extension : availableExtensions) {
        if (strcmp(extension.extensionName, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME) == 0)
            hasPortability = true;
    }
    const char *instanceExtensions[] = { VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME };
    const VkInstanceCreateInfo instanceInfo = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .flags = hasPortability ? VkInstanceCreateFlags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR) : VkInstanceCreateFlags(0),
        .pApplicationInfo = app,
        .enabledExtensionCount = hasPortability ? 1u : 0u,
        .ppEnabledExtensionNames = hasPortability ? instanceExtensions : nullptr,
    };
    VkResult result = vkCreateInstance(&instanceInfo, nullptr, &_instance);
    if (result != VK_SUCCESS) {
        if (error) *error = RMLVulkanError([NSString stringWithFormat:
            @"MoltenVK could not create a Vulkan instance (%d).", result]);
        return NO;
    }

    uint32_t count = 0;
    result = vkEnumeratePhysicalDevices(_instance, &count, nullptr);
    if (result != VK_SUCCESS || count == 0) {
        if (error) *error = RMLVulkanError(@"MoltenVK found no GPU device.");
        [self shutdown];
        return NO;
    }
    std::vector<VkPhysicalDevice> devices(count);
    result = vkEnumeratePhysicalDevices(_instance, &count, devices.data());
    if (result != VK_SUCCESS) {
        if (error) *error = RMLVulkanError(@"MoltenVK could not enumerate GPU devices.");
        [self shutdown];
        return NO;
    }
    _physicalDevice = devices[0];

    const char *deviceExtensions[] = { VK_EXT_METAL_OBJECTS_EXTENSION_NAME };
    retro_vulkan_context context{};
    if (!_negotiation.create_device ||
        !_negotiation.create_device(&context, _instance, _physicalDevice, VK_NULL_HANDLE,
                                    &vkGetInstanceProcAddr, deviceExtensions, 1,
                                    nullptr, 0, nullptr) ||
        !context.device || !context.queue) {
        if (error) *error = RMLVulkanError(@"Azahar could not create a MoltenVK device.");
        [self shutdown];
        return NO;
    }
    _device = context.device;
    _queue = context.queue;
    _queueFamily = context.queue_family_index;
    _interface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    _interface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    _interface.handle = (__bridge void *)self;
    _interface.instance = _instance;
    _interface.gpu = _physicalDevice;
    _interface.device = _device;
    _interface.get_device_proc_addr = &vkGetDeviceProcAddr;
    _interface.get_instance_proc_addr = &vkGetInstanceProcAddr;
    _interface.queue = _queue;
    _interface.queue_index = _queueFamily;
    _interface.set_image = &RMLSetImage;
    _interface.get_sync_index = &RMLGetSyncIndex;
    _interface.get_sync_index_mask = &RMLGetSyncIndexMask;
    _interface.wait_sync_index = &RMLWaitSyncIndex;
    _interface.lock_queue = &RMLLockQueue;
    _interface.unlock_queue = &RMLUnlockQueue;
    _contextActive = true;
    if (_renderCallback.context_reset) _renderCallback.context_reset();
    return YES;
}

- (BOOL)receiveVideoFrame:(const void *)data
                    width:(unsigned)width
                   height:(unsigned)height
                    error:(NSError **)error {
    if (self.submissionError != nil) {
        if (error) *error = RMLVulkanError(self.submissionError);
        return NO;
    }
    if (data == nullptr) return YES;
    if (data != RETRO_HW_FRAME_BUFFER_VALID || !_contextActive || !_hasPendingImage) {
        if (error) *error = RMLVulkanError(@"Azahar did not submit a Vulkan frame.");
        return NO;
    }
    // Azahar submits on the shared queue. Finish it before Metal uses the
    // underlying texture, then the next retro_run can safely reuse the image.
    {
        std::lock_guard<std::mutex> lock(_queueLock);
        if (vkQueueWaitIdle(_queue) != VK_SUCCESS) {
            if (error) *error = RMLVulkanError(@"The Azahar GPU queue did not complete.");
            return NO;
        }
    }
    auto exportObjects = (PFN_vkExportMetalObjectsEXT)
        vkGetDeviceProcAddr(_device, "vkExportMetalObjectsEXT");
    if (!exportObjects) {
        if (error) *error = RMLVulkanError(@"MoltenVK cannot export a Metal texture.");
        return NO;
    }
    VkExportMetalTextureInfoEXT textureInfo = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_METAL_TEXTURE_INFO_EXT,
        .image = _pendingImage.create_info.image,
        .imageView = _pendingImage.image_view,
        .plane = VK_IMAGE_ASPECT_COLOR_BIT,
    };
    VkExportMetalObjectsInfoEXT exportInfo = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT,
        .pNext = &textureInfo,
    };
    exportObjects(_device, &exportInfo);
    if (!textureInfo.mtlTexture) {
        if (error) *error = RMLVulkanError(@"MoltenVK returned no Metal frame texture.");
        return NO;
    }
    self.latestTexture = textureInfo.mtlTexture;
    self.width = width;
    self.height = height;
    _hasPendingImage = false;
    return YES;
}

- (void)invalidateFrame {
    self.latestTexture = nil;
    self.width = 0;
    self.height = 0;
    _hasPendingImage = false;
}

- (void)shutdown {
    [self invalidateFrame];
    self.submissionError = nil;
    if (_contextActive && _renderCallback.context_destroy)
        _renderCallback.context_destroy();
    _contextActive = false;
    if (_device) {
        vkDeviceWaitIdle(_device);
        vkDestroyDevice(_device, nullptr);
        _device = VK_NULL_HANDLE;
        _queue = VK_NULL_HANDLE;
    }
    if (_instance) {
        vkDestroyInstance(_instance, nullptr);
        _instance = VK_NULL_HANDLE;
    }
}

@end
