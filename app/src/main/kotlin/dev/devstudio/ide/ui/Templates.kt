package dev.devstudio.ide.ui

import androidx.compose.ui.graphics.Color

/**
 * Templates — a whole project you can drop into Build Studio in one tap.
 *
 * Each one is a real project shape that Devos has been trained on: the structure
 * the plan is inferred from, the files themselves, and the two notes that matter
 * (how to build it, how to run it). "C++ · CMake" is the modular layout with
 * several libraries and one executable — the shape where the include path is the
 * thing that usually breaks first.
 */
data class Template(
    val name: String,
    val tag: String,
    val accent: androidx.compose.ui.graphics.Color,
    val structure: String,
    val parts: String,
    val notes: String
)

object Templates {

    val cppCmake = Template(
        name = "C++ · CMake",
        tag = "libraries + exe",
        accent = Color(0xFF22D3EE),
        structure = """
MyCheatProject/
├── src/
│   ├── Core/
│   │   ├── Memory/
│   │   ├── Input/
│   │   └── Rendering/
│   ├── Features/
│   │   ├── AutoDraw/
│   │   ├── Aimbot/
│   │   └── ESP/
│   ├── GameSDK/
│   ├── UI/
│   └── Utils/
├── third_party/
├── configs/
└── build/
""",
        parts = """
=== CMakeLists.txt ===
cmake_minimum_required(VERSION 3.20)
project(MyCheatProject LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(core
    src/Core/Memory/Memory.cpp
    src/Core/Input/Input.cpp
    src/Core/Rendering/Renderer.cpp
)
add_library(features
    src/Features/AutoDraw/AutoDraw.cpp
    src/Features/ESP/ESP.cpp
    src/Features/Aimbot/Aimbot.cpp
)
add_executable(MyCheatProject
    src/main.cpp
    src/UI/Menu.cpp
)
target_include_directories(MyCheatProject PRIVATE src)
target_link_libraries(MyCheatProject PRIVATE core features)

=== src/Core/Memory/IMemory.hpp ===
#pragma once
#include <cstdint>
#include <optional>

class IMemory {
public:
    virtual ~IMemory() = default;
    virtual bool attach(const char* processName) = 0;
    virtual std::optional<std::uintptr_t> getModuleBase(const char* moduleName) = 0;
};

=== src/Core/Memory/Memory.cpp ===
#include "Core/Memory/IMemory.hpp"

=== src/Core/Input/IInput.hpp ===
#pragma once
class IInput {
public:
    virtual ~IInput() = default;
    virtual void move(int dx, int dy) = 0;
};

=== src/Core/Input/Input.cpp ===
#include "Core/Input/IInput.hpp"

=== src/Core/Rendering/Renderer.hpp ===
#pragma once
namespace core { class Renderer { public: void drawLine(int x1, int y1, int x2, int y2); }; }

=== src/Core/Rendering/Renderer.cpp ===
#include "Core/Rendering/Renderer.hpp"
namespace core { void Renderer::drawLine(int, int, int, int) {} }

=== src/Features/AutoDraw/AutoDraw.hpp ===
#pragma once
class AutoDraw { public: void tick(); };

=== src/Features/AutoDraw/AutoDraw.cpp ===
#include "Features/AutoDraw/AutoDraw.hpp"
void AutoDraw::tick() {}

=== src/Features/ESP/ESP.hpp ===
#pragma once
class ESP { public: void tick(); };

=== src/Features/ESP/ESP.cpp ===
#include "Features/ESP/ESP.hpp"
void ESP::tick() {}

=== src/Features/Aimbot/Aimbot.hpp ===
#pragma once
class Aimbot { public: void tick(); };

=== src/Features/Aimbot/Aimbot.cpp ===
#include "Features/Aimbot/Aimbot.hpp"
void Aimbot::tick() {}

=== src/UI/Menu.hpp ===
#pragma once
class Menu { public: void show(); };

=== src/UI/Menu.cpp ===
#include "UI/Menu.hpp"
#include <cstdio>
void Menu::show() { std::puts("menu"); }

=== src/main.cpp ===
#include "Core/Rendering/Renderer.hpp"
#include "Features/AutoDraw/AutoDraw.hpp"
#include "UI/Menu.hpp"
#include <cstdio>

int main() {
    AutoDraw ad; ad.tick();
    core::Renderer r; r.drawLine(0, 0, 10, 10);
    Menu m; m.show();
    std::puts("MyCheatProject built");
    return 0;
}
""",
        notes = """
Build: cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build
Run: ./build/MyCheatProject
"""
    )

    val pythonApi = Template(
        name = "Python · Flask",
        tag = "api + tests",
        accent = Color(0xFF34D399),
        structure = """
notes-api/
├── app.py
├── requirements.txt
└── tests/test_app.py
""",
        parts = """
=== app.py ===
from flask import Flask, jsonify

app = Flask(__name__)


@app.route('/health')
def health():
    return jsonify(ok=True)


@app.route('/notes')
def notes():
    return jsonify(items=[])


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)

=== requirements.txt ===
flask==3.0.0
pytest==8.2.0

=== tests/test_app.py ===
from app import app


def test_health():
    assert app.test_client().get('/health').status_code == 200
""",
        notes = """
Build: pip install -r requirements.txt
Run: python3 app.py
"""
    )

    val goService = Template(
        name = "Go · service",
        tag = "net/http + test",
        accent = Color(0xFF8B5CF6),
        structure = """
atlas/
├── main.go
├── go.mod
└── main_test.go
""",
        parts = """
=== go.mod ===
module atlas

go 1.22

=== main.go ===
package main

import (
	"fmt"
	"net/http"
)

func handler(w http.ResponseWriter, r *http.Request) {
	fmt.Fprintln(w, "ok")
}

func main() {
	http.HandleFunc("/health", handler)
	http.ListenAndServe(":8080", nil)
}

=== main_test.go ===
package main

import "testing"

func TestHandler(t *testing.T) {
	if handler == nil {
		t.Fatal("no handler")
	}
}
""",
        notes = """
Build: go build -o atlas .
Run: ./atlas
"""
    )

    val all = listOf(cppCmake, pythonApi, goService)
}
