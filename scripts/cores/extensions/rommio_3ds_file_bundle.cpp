// SPDX-License-Identifier: MIT
#include "rommio_3ds_file_bundle.h"
#include <algorithm>
#include <atomic>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <set>
#include <stdexcept>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace rommio_files {
namespace fs = std::filesystem;
namespace {
constexpr unsigned max_entries = 1024;
const uint8_t magic[] = {'R','M','M','F','0','0','0','1'};
struct Entry { std::string path; uint8_t kind; uint64_t seconds; uint32_t nanos; std::vector<uint8_t> data; };
void need(bool ok) { if (!ok) throw std::runtime_error("Invalid or incomplete memory-stick bundle"); }
void put(std::vector<uint8_t>& out, uint64_t v, unsigned n) { for(unsigned i=0;i<n;i++) out.push_back(uint8_t(v>>(8*i))); }
uint64_t get(const std::vector<uint8_t>& in, size_t& at, unsigned n) {
   need(at <= in.size() && n <= in.size()-at); uint64_t v=0;
   for(unsigned i=0;i<n;i++) v|=uint64_t(in[at++])<<(8*i); return v;
}
std::string folded(std::string s) { for(auto& c:s) if (c >= 'A' && c <= 'Z') c+=32; return s; }
void valid_path(const std::string& s) {
   need(!s.empty() && s.size()<=512 && s[0]!='/' && s.back()!='/');
   size_t from=0; unsigned depth=0;
   for(size_t i=0;i<=s.size();i++) {
      if(i<s.size()) need(s[i]!='\\' && s[i]!=':' && uint8_t(s[i])>=32 && uint8_t(s[i])!=127);
      if(i==s.size() || s[i]=='/') { auto part=s.substr(from,i-from); need(!part.empty() && part!="." && part!="..");from=i+1;need(++depth<=16); }
   }
}
bool selected(const std::string& path, bool directory, Profile profile) {
   if(profile==Profile::CompleteTree) return true;
   if(path=="sdmc") return true;
   if(path.rfind("sdmc/",0)==0) {
      const auto lower=folded(path);
      // This known 3DStris diagnostic changes during play. Unknown .log files
      // may be game data and stay in the complete portable inventory.
      return directory || lower!="sdmc/3ds/3dstris/log.log";
   }
   if(path=="nand" || path=="nand/title" || path=="nand/data") return true;
   if(path.rfind("nand/title/",0)==0) return true;
   if(path.rfind("nand/data/",0)!=0) return false;
   // A per-title NAND extdata tree is portable. Console sysdata and the
   // 00048000 system-extdata category (play coins, StreetPass) are not.
   const auto first=path.find('/',10);
   if(first==std::string::npos) return directory; // nand/data/<console id>
   const auto branch=path.substr(first+1);
   if(branch=="extdata") return directory;
   if(branch.rfind("extdata/",0)!=0) return false;
   const auto category=branch.substr(8,branch.find('/',8)-8);
   return category!="00048000";
}
void validate(const std::vector<Entry>& entries) {
   need(entries.size()<=max_entries); std::set<std::string> seen, dirs;
   std::string previous;
   for(const auto& e:entries) {
      valid_path(e.path); need(previous.empty() || previous<e.path);previous=e.path;
      auto key=folded(e.path); need(seen.insert(key).second);
      auto slash=key.rfind('/'); if(slash!=std::string::npos) need(dirs.count(key.substr(0,slash)));
      need(e.kind>=1 && e.kind<=3 && e.nanos<1000000000);
      if(e.kind==1) { need(e.data.empty() && e.seconds==0 && e.nanos==0);dirs.insert(key); }
   }
}
std::vector<uint8_t> encode(const std::vector<Entry>& entries) {
   validate(entries);
   std::vector<uint8_t> bytes(std::begin(magic),std::end(magic));put(bytes,entries.size(),4);
   for(const auto& e:entries) {
      put(bytes,e.path.size(),2);put(bytes,e.kind,1);put(bytes,0,1);put(bytes,e.data.size(),8);put(bytes,e.seconds,8);put(bytes,e.nanos,4);
      bytes.insert(bytes.end(),e.path.begin(),e.path.end());bytes.insert(bytes.end(),e.data.begin(),e.data.end());
   }
   return bytes;
}
std::vector<Entry> decode(const std::vector<uint8_t>& bytes) {
   need(bytes.size()>=12 && bytes.size()<=max_bytes && std::equal(std::begin(magic),std::end(magic),bytes.begin()));
   size_t at=8; auto count=get(bytes,at,4);need(count<=max_entries); std::vector<Entry> result;
   for(unsigned i=0;i<count;i++) {
      auto length=get(bytes,at,2);auto kind=get(bytes,at,1);need(get(bytes,at,1)==0);
      auto size=get(bytes,at,8);auto seconds=get(bytes,at,8);auto nanos=get(bytes,at,4);
      need(length>0 && length<=512 && at<=bytes.size() && length<=bytes.size()-at);
      std::string path(reinterpret_cast<const char*>(bytes.data()+at),length);at+=length;
      need(size<=max_bytes && size<=bytes.size()-at);
      result.push_back({path,uint8_t(kind),seconds,uint32_t(nanos),{bytes.begin()+at,bytes.begin()+at+size}});at+=size;
   }
   need(at==bytes.size());validate(result);return result;
}
void safe_root(const fs::path& root) {
   need(fs::is_directory(root) && !fs::is_symlink(root));
   // Parent directories are supplied by the app; /var is a system symlink on iOS.
}
}
std::vector<uint8_t> capture(const std::string& rootString, Profile profile) {
   const fs::path root(rootString);safe_root(root);
   std::vector<Entry> entries; size_t total=12;
   for(fs::recursive_directory_iterator it(root),end;it!=end;++it) {
      const auto& item=*it;
      const auto path=item.path().lexically_relative(root).generic_string();
      const bool directory=fs::is_directory(item.symlink_status());
      if(!selected(path,directory,profile)) {
         if(directory) it.disable_recursion_pending();
         continue;
      }
      need(entries.size()<max_entries && !item.is_symlink());
      Entry entry{path,1,0,0,{}};valid_path(entry.path);
      if(!directory) {
         need(item.is_regular_file());const auto size=item.file_size();
         need(size<=max_bytes && total<=max_bytes-size);total+=size;
         struct stat st{};need(lstat(item.path().c_str(),&st)==0 && S_ISREG(st.st_mode));
         entry.kind=(st.st_mode&S_IWUSR)?2:3;
#if defined(__APPLE__)
         entry.seconds=st.st_mtimespec.tv_sec;entry.nanos=st.st_mtimespec.tv_nsec;
#else
         entry.seconds=st.st_mtim.tv_sec;entry.nanos=st.st_mtim.tv_nsec;
#endif
         entry.data.resize(size);std::ifstream input(item.path(),std::ios::binary);
         need(bool(input)); if(size) { input.read(reinterpret_cast<char*>(entry.data.data()),size);need(size_t(input.gcount())==size); }
         need(input.peek()==std::char_traits<char>::eof());
      }
      total+=24+entry.path.size();need(total<=max_bytes);entries.push_back(std::move(entry));
   }
   std::sort(entries.begin(),entries.end(),[](const Entry&a,const Entry&b){return a.path<b.path;});validate(entries);
   return encode(entries);
}
bool restore(const std::string& rootString,const std::vector<uint8_t>& bytes,void (*close_handles)(),Profile profile) {
   fs::path stage,backup;bool moved=false,installed=false,ownsStage=false;
   try {
      const auto incoming=decode(bytes);const fs::path root(rootString);safe_root(root);
      std::vector<Entry> entries;
      for(const auto& e:incoming) if(selected(e.path,e.kind==1,profile)) entries.push_back(e);
      static std::atomic<unsigned> sequence{0};
      stage=root.parent_path()/ (".rommio-files-"+std::to_string(getpid())+"-"+std::to_string(++sequence));
      need(fs::create_directory(stage));ownsStage=true;backup=stage/"previous";const auto prepared=stage/"next";need(fs::create_directory(prepared));
      for(const auto&e:entries) {
         const auto target=prepared/e.path;
         if(e.kind==1) { need(fs::create_directory(target));continue; }
         std::ofstream output(target,std::ios::binary|std::ios::trunc);need(bool(output));
         output.write(reinterpret_cast<const char*>(e.data.data()),e.data.size());output.close();need(bool(output));
         struct timespec ts[2]={{0,UTIME_OMIT},{time_t(e.seconds),long(e.nanos)}};
         need(utimensat(AT_FDCWD,target.c_str(),ts,0)==0);
         need(chmod(target.c_str(),e.kind==3?0400:0600)==0);
      }
      need(capture(prepared.string(),profile)==encode(entries));
      // Keep the receiving console's sysdata and system extdata while replacing
      // the complete game-data inventory. Legacy full-tree bundles are filtered
      // on restore, so their old device's shared NAND files are never imported.
      if(profile==Profile::ThreeDSGameData) {
         for(const auto& item:fs::recursive_directory_iterator(root)) {
            const auto path=item.path().lexically_relative(root).generic_string();
            const bool directory=fs::is_directory(item.symlink_status());
            if(selected(path,directory,profile)) continue;
            need(!item.is_symlink());
            const auto target=prepared/path;
            if(directory) { fs::create_directories(target);continue; }
            need(item.is_regular_file());fs::create_directories(target.parent_path());
            fs::copy_file(item.path(),target,fs::copy_options::none);
            fs::permissions(target,fs::status(item).permissions());
            fs::last_write_time(target,fs::last_write_time(item));
         }
      }
      close_handles();fs::rename(root,backup);moved=true;
      fs::rename(prepared,root);installed=true;
      fs::remove_all(stage);return true;
   } catch(...) {
      std::error_code ec;
      if(moved && !installed) fs::rename(backup,fs::path(rootString),ec);
      // Retain a backup if rollback itself failed.
      if(ownsStage && !stage.empty() && (!moved || installed || !fs::exists(backup))) fs::remove_all(stage,ec);
      return false;
   }
}
}
