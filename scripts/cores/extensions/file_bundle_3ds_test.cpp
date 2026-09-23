// SPDX-License-Identifier: MIT
#include "rommio_3ds_file_bundle.h"
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

    // A 3DS bundle contains game SD data and title NAND data, while console
    // sysdata, system extdata, and logs remain local to the receiving device.
    const auto threeDS=temp/"three-ds";
    fs::create_directories(threeDS/"sdmc/Nintendo 3DS/console/title/00040000/00055d00/data");
    fs::create_directories(threeDS/"sdmc/Nintendo 3DS/console/extdata/00000000/0000055D");
    fs::create_directories(threeDS/"sdmc/3ds/3dstris");
    fs::create_directories(threeDS/"nand/title/00040000/00055d00/data");
    fs::create_directories(threeDS/"nand/data/console/extdata/00000000/0000055D");
    fs::create_directories(threeDS/"nand/data/console/extdata/00048000/F000000B/user");
    fs::create_directories(threeDS/"nand/data/console/sysdata/00010026");
    write(threeDS/"sdmc/Nintendo 3DS/console/title/00040000/00055d00/data/main","old game");
    write(threeDS/"sdmc/Nintendo 3DS/console/extdata/00000000/0000055D/progress","old extdata");
    write(threeDS/"sdmc/3ds/3dstris/config.mp","old homebrew save");
    write(threeDS/"sdmc/3ds/3dstris/log.log","old log");
    write(threeDS/"sdmc/3ds/3dstris/progress.log","game data with a log extension");
    write(threeDS/"nand/title/00040000/00055d00/data/main","old NAND save");
    write(threeDS/"nand/data/console/extdata/00000000/0000055D/progress","old NAND extdata");
    write(threeDS/"nand/data/console/extdata/00048000/F000000B/user/gamecoin.dat","old coins");
    write(threeDS/"nand/data/console/sysdata/00010026/eventlog.dat","old system log");
    using rommio_files::Profile;
    const auto legacy3DS=rommio_files::capture(threeDS.string());
    const auto game3DS=rommio_files::capture(threeDS.string(),Profile::ThreeDSGameData);
    assert(game3DS.size()<legacy3DS.size());
    write(threeDS/"sdmc/3ds/3dstris/log.log","new log");
    write(threeDS/"nand/data/console/extdata/00048000/F000000B/user/gamecoin.dat","new coins");
    write(threeDS/"nand/data/console/sysdata/00010026/eventlog.dat","new system log");
    assert(rommio_files::capture(threeDS.string(),Profile::ThreeDSGameData)==game3DS);
    write(threeDS/"sdmc/3ds/3dstris/progress.log","new game data");
    assert(rommio_files::capture(threeDS.string(),Profile::ThreeDSGameData)!=game3DS);
    write(threeDS/"sdmc/3ds/3dstris/progress.log","game data with a log extension");
    write(threeDS/"sdmc/Nintendo 3DS/console/title/00040000/00055d00/data/main","new game");
    assert(rommio_files::capture(threeDS.string(),Profile::ThreeDSGameData)!=game3DS);
    // A legacy complete-tree checkpoint must not import its source console's
    // system files, but it must still recover every game-save location.
    assert(rommio_files::restore(threeDS.string(),legacy3DS,close_handles,Profile::ThreeDSGameData));
    assert(fs::file_size(threeDS/"sdmc/Nintendo 3DS/console/title/00040000/00055d00/data/main")==8);
    assert(rommio_files::capture(threeDS.string(),Profile::ThreeDSGameData)==game3DS);
    auto contents=[](const fs::path& p) { std::ifstream in(p);return std::string(std::istreambuf_iterator<char>(in),{}); };
    assert(contents(threeDS/"sdmc/3ds/3dstris/log.log")=="new log");
    assert(contents(threeDS/"nand/data/console/extdata/00048000/F000000B/user/gamecoin.dat")=="new coins");
    assert(contents(threeDS/"nand/data/console/sysdata/00010026/eventlog.dat")=="new system log");
    assert(contents(threeDS/"nand/title/00040000/00055d00/data/main")=="old NAND save");
    assert(contents(threeDS/"nand/data/console/extdata/00000000/0000055D/progress")=="old NAND extdata");
    write(threeDS/"sdmc/Nintendo 3DS/console/title/00040000/00055d00/data/main","new game");
    assert(rommio_files::restore(threeDS.string(),game3DS,close_handles,Profile::ThreeDSGameData));
    assert(contents(threeDS/"sdmc/Nintendo 3DS/console/title/00040000/00055d00/data/main")=="old game");
    assert(contents(threeDS/"nand/data/console/sysdata/00010026/eventlog.dat")=="new system log");
    // A checkpoint made before any game writes still restores on a console
    // that already has its own NAND system data.
    const auto noGame=temp/"no-game";
    fs::create_directory(noGame);
    const auto noGameCheckpoint=rommio_files::capture(noGame.string(),Profile::ThreeDSGameData);
    fs::create_directories(noGame/"nand/data/console/sysdata/00010017");
    write(noGame/"nand/data/console/sysdata/00010017/config","recipient config");
    assert(rommio_files::restore(noGame.string(),noGameCheckpoint,close_handles,Profile::ThreeDSGameData));
    assert(contents(noGame/"nand/data/console/sysdata/00010017/config")=="recipient config");
    fs::remove_all(temp);
}
