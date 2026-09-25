import os
from setuptools import setup, find_packages

here = os.path.abspath(os.path.dirname(__file__))
try:
    with open(os.path.join(here, "README.md"), encoding="utf-8") as f:
        long_description = f.read()
except FileNotFoundError:
    long_description = "NexusSky UAV intelligent flight control middleware - Python SDK"

setup(
    name="aerofleet-sdk",
    version="1.0.0",
    description="NexusSky UAV intelligent flight control middleware - Python SDK",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/Levango7/NexusSky",
    author="NexusSky Team",
    author_email="support@nexussky.io",
    license="Apache License 2.0",
    packages=find_packages(),
    python_requires=">=3.8",
    install_requires=[
        "requests>=2.25.0",
    ],
    classifiers=[
        "Development Status :: 4 - Beta",
        "Intended Audience :: Developers",
        "License :: OSI Approved :: Apache Software License",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Topic :: Software Development :: Libraries",
    ],
)