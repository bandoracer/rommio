// SPDX-License-Identifier: MIT
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace rommio_files {
constexpr size_t max_bytes = 64u * 1024u * 1024u;
enum class Profile { CompleteTree, ThreeDSGameData };
std::vector<uint8_t> capture(const std::string& root, Profile profile = Profile::CompleteTree);
// Parse and stage every entry before closing handles or changing the workspace.
bool restore(const std::string& root, const std::vector<uint8_t>& bytes, void (*close_handles)(),
             Profile profile = Profile::CompleteTree);
}
