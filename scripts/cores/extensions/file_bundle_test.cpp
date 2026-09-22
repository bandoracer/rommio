// SPDX-License-Identifier: MIT
#include "rommio_file_bundle.h"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <unistd.h>
#include <sys/stat.h>
namespace fs = std::filesystem;
static int closed;
static void close_handles() { ++closed; }
static void write(const fs::path& p, const std::string& s) { std::ofstream out(p, std::ios::binary); out << s; }
int main() {
    const auto temp = fs::temp_directory_path()/("rommio-bundle-test-"+std::to_string(getpid()));
    fs::create_directories(temp/"saves/PSP/SAVEDATA/TEST00001");
    const auto root = temp/"saves"; const auto game = root/"PSP/SAVEDATA/TEST00001";
    fs::create_directory(game/"empty"); write(game/"DATA.BIN", "first save"); write(game/"PARAM.SFO", "parameters");
    write(game/"EMPTY.BIN", ""); chmod((game/"PARAM.SFO").c_str(),0400);
    const auto bytes = rommio_files::capture(root.string());
    write(game/"DATA.BIN", "later progress"); fs::remove(game/"EMPTY.BIN"); write(game/"extra", "new");
    const auto changed = rommio_files::capture(root.string()); assert(changed != bytes);
    // All truncated inputs must fail before closing any active handles or changing files.
    for(size_t n=0;n<bytes.size();++n) {
        assert(!rommio_files::restore(root.string(), {bytes.begin(),bytes.begin()+n}, close_handles));
    }
    auto invalid = bytes; invalid[0] ^= 1;
    assert(!rommio_files::restore(root.string(), invalid, close_handles));
    invalid=bytes; invalid[36]='/'; // First path begins after the entry header.
    assert(!rommio_files::restore(root.string(), invalid, close_handles));
    assert(closed==0 && rommio_files::capture(root.string())==changed);
    assert(rommio_files::restore(root.string(), bytes, close_handles));
    assert(closed==1 && rommio_files::capture(root.string())==bytes);
    fs::create_symlink(game/"DATA.BIN", game/"LINK");
    bool rejected=false;try { rommio_files::capture(root.string()); } catch(...) { rejected=true; }
    assert(rejected);fs::remove(game/"LINK");
    // Empty inventories replace an existing tree; game saves with no files are still complete.
    fs::create_directory(temp/"empty");auto empty=rommio_files::capture((temp/"empty").string());
    assert(empty.size()==12 && rommio_files::restore(root.string(),empty,close_handles));
    assert(fs::is_empty(root));
    fs::remove_all(temp);
}
